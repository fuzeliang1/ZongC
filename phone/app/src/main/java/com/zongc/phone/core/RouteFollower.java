package com.zongc.phone.core;

import java.util.List;
import java.util.Map;

/**
 * 手机车：沿数字世界下发的路线"开车"，人走路的速度就是油门。
 *
 * <p>课程要求"以人携带手机运动来模拟车辆的运动"。人不可能在教室里沿着地图上的街道走，所以车的
 * <b>位置</b>由服务端规划的路线决定，车的<b>快慢</b>由人走路决定：人走 1 m，车沿路线开 driveScale 米；
 * 人停下，车就停下。设备协议与 Matlab 模拟车相同（docs/protocol.md 2.5 节）：</p>
 * <ul>
 *   <li>welcome：绑定的数字车编号、当前位姿和车辆参数（最高车速、加减速度、转弯限速）；</li>
 *   <li>path：路线折线 waypoints（相邻点不超过 1/4 格，转弯处沿圆弧采样）。新路线在收到它的 permit 之前不能走；</li>
 *   <li>permit：沿折线的累计长度 stopS。车的弧长 s 任何时候都不能超过它；直接采用最新一条
 *       （授权可能被收回，不能取历史最大值）。</li>
 * </ul>
 * <p>每拍（{@link #step}）把期望车速限制在最高车速、转弯限速和"在 stopS 前刹得住"的刹车曲线以内，
 * 再按加减速度平滑，然后沿折线推进 s，输出 s 处的位姿 {x, y, θ, v, ω}，由 {@link DwConnection} 以 10 Hz 上报。
 * 车永远在路线上，所以不会撞墙；什么时候能走由服务端的移动授权决定，所以不会撞车。</p>
 *
 * <p>所有公开方法都是 synchronized 的：网络读线程收消息，上报线程推进，界面线程读状态。</p>
 */
public final class RouteFollower {
    /** 新路线投影时，允许车位于第一个路点之前的最大距离（米）：车刚好在路线起点后面一点也能接上。 */
    private static final double BEFORE_START = 1.0;
    /** 相邻两段的航向差超过它（弧度）就算转弯段，要限速。直线段的差为 0，圆弧段约 0.2~0.4。 */
    private static final double TURN_EPS = 0.05;

    /** 某一时刻的状态（界面每 0.1 s 读一次）。 */
    public static final class Status {
        /** 已收到 welcome（绑定了数字车）。 */
        public boolean ready;
        public String agentId;
        public double x;
        public double y;
        public double theta;
        /** 车速（地图米/秒）。 */
        public double v;
        /** 期望车速：走路速度 × driveScale（米/秒）。 */
        public double wanted;

        public boolean hasRoute;
        public int routeId = -1;
        /** 服务端给这条路线的用途：CRUISE / PICKUP / DROPOFF。 */
        public String purpose;
        /** 车的状态：CRUISING / TO_PICKUP / BOARDING / TO_DROPOFF / ALIGHTING。 */
        public String carStatus;
        public String taskId;
        /** 已沿路线走过的长度、路线总长、授权停车位置（都是沿折线的累计长度，米）。 */
        public double s;
        public double length;
        public double stopS;
        /** 是否收到了这条路线的授权；授权是否已到终点。 */
        public boolean permitted;
        public boolean finalPermit;
        /** 授权停车点在地图上的位置（没有路线时为 NaN）。 */
        public double stopX = Double.NaN;
        public double stopY = Double.NaN;
        /** 路线折线 [x0, y0, x1, y1, ...]（画小地图用，不要修改）。 */
        public double[] waypoints;
        /** 车前方第一个路点、授权停车点之后第一个路点的序号（画剩余路线用）。 */
        public int next;
        public int stopNext;

        /** 剩余路程（米）。 */
        public double remaining() {
            return hasRoute ? Math.max(0, length - s) : 0;
        }

        /** 已到路线终点。 */
        public boolean arrived() {
            return hasRoute && s >= length - 0.01;
        }

        /** 车停在授权停车点前、还没到终点：在等前方让出格子，走路也不会动。 */
        public boolean waitingPermit() {
            return hasRoute && !arrived() && s >= Math.min(stopS, length) - 0.05;
        }
    }

    // 车辆参数（welcome.params 会覆盖）
    private double vMax = 1.5;
    private double aAcc = 1.0;
    private double aDec = 1.5;
    private double vTurn = 0.6;

    private boolean ready;
    private String agentId;
    private double x;
    private double y;
    private double theta;
    private double v;
    private double w;
    private double wanted;
    private long lastT;
    private boolean hasT;

    // 路线：路点、累计长度、每段的航向和是否转弯
    private double[] wx;
    private double[] wy;
    private double[] cum;
    private double[] heading;
    private boolean[] turning;
    private double[] flat;
    private int routeId = -1;
    private String purpose;
    private String carStatus;
    private String taskId;
    private double s;
    private double stopS;
    private boolean permitted;
    private boolean finalPermit;

    /** 处理一条服务端消息（welcome / path / permit），其他类型忽略。 */
    public synchronized void onMessage(Map<String, Object> m) {
        if (m == null) return;
        String type = Json.str(m, "type");
        if ("welcome".equals(type)) onWelcome(m);
        else if ("path".equals(type)) onPath(m);
        else if ("permit".equals(type)) onPermit(m);
    }

    /** 断线后清空：重连时服务端会重新发 welcome、path、permit。 */
    public synchronized void reset() {
        ready = false;
        agentId = null;
        v = 0;
        w = 0;
        hasT = false;
        clearRoute();
    }

    /**
     * 推进一拍。
     *
     * @param now    当前时刻（ns，任意单调时基）
     * @param wanted 期望车速（米/秒）：人在走路时 = 走路速度 × driveScale，停下时 = 0
     * @return 当前位姿 {x, y, θ, v, ω}；还没收到 welcome 时返回 null（这一拍不上报）
     */
    public synchronized double[] step(long now, double wanted) {
        if (!ready) return null;
        double dt = hasT ? Geo.clamp((now - lastT) * 1e-9, 0, 0.5) : 0;
        lastT = now;
        hasT = true;
        this.wanted = Double.isNaN(wanted) ? 0 : Math.max(0, wanted);
        double oldTheta = theta;
        if (wx == null) {
            v = 0;
        } else {
            double limit = Math.min(stopS, cum[cum.length - 1]);
            double room = limit - s;
            // 硬约束：最高车速、转弯限速、在授权停车点前刹得住
            double cap = room <= 1e-6 ? 0 : Math.min(Math.min(vMax, Math.sqrt(2 * aDec * room)), turnLimit(s));
            double target = Math.min(this.wanted, cap);
            double next = target > v ? Math.min(target, v + aAcc * dt) : Math.max(target, v - aDec * dt);
            next = Math.min(next, cap);
            double ds = Math.min(next * dt, Math.max(0, room));
            s += ds;
            v = dt > 0 ? ds / dt : 0;
            place();
        }
        w = dt > 0 ? Geo.diff(theta, oldTheta) / dt : 0;
        return new double[]{x, y, theta, v, w};
    }

    /** 当前位姿 {x, y, θ, v, ω}（不推进）；还没收到 welcome 时返回 null。 */
    public synchronized double[] pose() {
        return ready ? new double[]{x, y, theta, v, w} : null;
    }

    public synchronized Status status() {
        Status st = new Status();
        st.ready = ready;
        st.agentId = agentId;
        st.x = x;
        st.y = y;
        st.theta = theta;
        st.v = v;
        st.wanted = wanted;
        st.hasRoute = wx != null;
        st.routeId = routeId;
        st.purpose = purpose;
        st.carStatus = carStatus;
        st.taskId = taskId;
        if (wx != null) {
            st.s = s;
            st.length = cum[cum.length - 1];
            st.stopS = stopS;
            st.permitted = permitted;
            st.finalPermit = finalPermit;
            double stopAt = Math.max(s, Math.min(stopS, st.length));
            double[] p = pointAt(stopAt);
            st.stopX = p[0];
            st.stopY = p[1];
            st.waypoints = flat;
            st.next = Math.min(wx.length - 1, segmentAt(s) + 1);
            st.stopNext = Math.min(wx.length - 1, segmentAt(stopAt) + 1);
        }
        return st;
    }

    // ================================================================ 消息

    private void onWelcome(Map<String, Object> m) {
        agentId = Json.str(m, "agentId");
        Map<String, Object> pose = Json.obj(m.get("pose"));
        x = Json.num(pose, "x", x);
        y = Json.num(pose, "y", y);
        theta = Json.num(pose, "theta", theta);
        v = 0;
        w = 0;
        Map<String, Object> p = Json.obj(m.get("params"));
        vMax = positive(p, "vMax", vMax);
        aAcc = positive(p, "aAcc", aAcc);
        aDec = positive(p, "aDec", aDec);
        vTurn = Math.min(vMax, positive(p, "vTurn", vTurn));
        clearRoute();
        ready = true;
    }

    private void onPath(Map<String, Object> m) {
        List<Object> list = Json.arr(m.get("waypoints"));
        if (list == null || list.isEmpty()) return;
        int n = list.size();
        double[] px = new double[n];
        double[] py = new double[n];
        for (int i = 0; i < n; i++) {
            List<Object> pt = Json.arr(list.get(i));
            if (pt == null || pt.size() < 2 || !(pt.get(0) instanceof Number) || !(pt.get(1) instanceof Number)) return;
            px[i] = ((Number) pt.get(0)).doubleValue();
            py[i] = ((Number) pt.get(1)).doubleValue();
        }
        wx = px;
        wy = py;
        cum = new double[n];
        for (int i = 1; i < n; i++) cum[i] = cum[i - 1] + Math.hypot(px[i] - px[i - 1], py[i] - py[i - 1]);
        buildHeadings();
        flat = new double[2 * n];
        for (int i = 0; i < n; i++) {
            flat[2 * i] = px[i];
            flat[2 * i + 1] = py[i];
        }
        routeId = (int) Json.num(m, "routeId", -1);
        purpose = Json.str(m, "purpose");
        carStatus = Json.str(m, "status");
        taskId = Json.str(m, "taskId");
        s = project(x, y);
        stopS = 0;                 // 新路线在收到它的 permit 之前不能走
        permitted = false;
        finalPermit = false;
        place();
    }

    private void onPermit(Map<String, Object> m) {
        if (wx == null || (int) Json.num(m, "routeId", -2) != routeId) return;   // 旧路线的授权：忽略
        stopS = Json.num(m, "stopS", 0);
        finalPermit = Json.bool(m, "final", false);
        permitted = true;
    }

    private void clearRoute() {
        wx = null;
        wy = null;
        cum = null;
        heading = null;
        turning = null;
        flat = null;
        routeId = -1;
        purpose = null;
        carStatus = null;
        taskId = null;
        s = 0;
        stopS = 0;
        permitted = false;
        finalPermit = false;
    }

    // ================================================================ 几何

    /** 每段的航向（零长度段取后一段的，用于原地转向）与是否属于转弯。 */
    private void buildHeadings() {
        int segs = Math.max(1, wx.length - 1);
        heading = new double[segs];
        turning = new boolean[segs];
        double next = Double.NaN;
        for (int i = segs - 1; i >= 0; i--) {
            if (i + 1 < wx.length && cum[i + 1] - cum[i] > 1e-9) {
                next = Math.atan2(wy[i + 1] - wy[i], wx[i + 1] - wx[i]);
            }
            heading[i] = next;
        }
        double prev = Double.NaN;
        for (int i = 0; i < segs; i++) {
            if (Double.isNaN(heading[i])) heading[i] = Double.isNaN(prev) ? theta : prev;
            turning[i] = i > 0 && Math.abs(Geo.diff(heading[i], heading[i - 1])) > TURN_EPS;
            prev = heading[i];
        }
    }

    /** s 所在的段号：cum[k] ≤ s < cum[k+1]；s 超出两端时取第一段 / 最后一段。 */
    private int segmentAt(double s) {
        int segs = heading.length;
        if (s <= 0) return 0;
        int lo = 0;
        int hi = segs - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cum[mid] <= s) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** 弧长 s 处的点；s &lt; 0 时沿第一段反向延长（车在路线起点后面一点）。 */
    private double[] pointAt(double s) {
        int n = wx.length;
        if (n == 1) return new double[]{wx[0], wy[0]};
        double len = cum[n - 1];
        if (s >= len) return new double[]{wx[n - 1], wy[n - 1]};
        if (s < 0) {
            double h = heading[0];
            return new double[]{wx[0] + s * Math.cos(h), wy[0] + s * Math.sin(h)};
        }
        int k = segmentAt(s);
        double segLen = cum[k + 1] - cum[k];
        double t = segLen > 1e-12 ? (s - cum[k]) / segLen : 0;
        return new double[]{wx[k] + (wx[k + 1] - wx[k]) * t, wy[k] + (wy[k + 1] - wy[k]) * t};
    }

    /** 把位姿放到折线上 s 处，航向取所在段的方向。 */
    private void place() {
        double[] p = pointAt(s);
        x = p[0];
        y = p[1];
        int k = s >= cum[cum.length - 1] ? heading.length - 1 : segmentAt(s);
        theta = heading[k];
    }

    /**
     * 把 (px, py) 投影到折线上，返回弧长。路线可能经过同一处两次，所以在距离差不多（相差 5 cm 以内）的候选里取最靠前的。
     */
    private double project(double px, double py) {
        int n = wx.length;
        if (n == 1) return 0;
        int segs = n - 1;
        double[] ds = new double[segs];
        double[] ss = new double[segs];
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < segs; i++) {
            double len = cum[i + 1] - cum[i];
            double t;
            if (len < 1e-12) {
                t = 0;
            } else {
                t = ((px - wx[i]) * (wx[i + 1] - wx[i]) + (py - wy[i]) * (wy[i + 1] - wy[i])) / (len * len);
                double lo = i == 0 ? -BEFORE_START / len : 0;
                t = Geo.clamp(t, lo, 1);
            }
            double qx = wx[i] + (wx[i + 1] - wx[i]) * t;
            double qy = wy[i] + (wy[i + 1] - wy[i]) * t;
            ds[i] = Math.hypot(px - qx, py - qy);
            ss[i] = cum[i] + t * len;
            best = Math.min(best, ds[i]);
        }
        for (int i = 0; i < segs; i++) if (ds[i] <= best + 0.05) return ss[i];
        return 0;
    }

    /** 转弯限速：前方第一个转弯段要在到达前减到 vTurn（v² = vTurn² + 2·aDec·d），在转弯段里不超过 vTurn。 */
    private double turnLimit(double s) {
        double look = vMax * vMax / (2 * aDec) + 0.5;
        for (int k = segmentAt(s); k < turning.length && cum[k] < s + look; k++) {
            if (turning[k]) return Math.sqrt(vTurn * vTurn + 2 * aDec * Math.max(0, cum[k] - s));
        }
        return vMax;
    }

    private static double positive(Map<String, Object> m, String key, double def) {
        double v = Json.num(m, key, def);
        return v > 0 && !Double.isInfinite(v) ? v : def;
    }
}
