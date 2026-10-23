package com.zongc.dw.world;

import com.zongc.dw.map.GridMap;
import com.zongc.dw.util.Num;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 环境感知与辅助建图（综设 C 的"智能"要求：知道周边有什么障碍物和目标物，能辅助建地图）。
 *
 * <h3>真实世界与系统认知是两回事</h3>
 * 场景脚本可以在真实世界里放置<b>未知障碍</b>（{@link #hidden}），系统一开始并不知道，
 * 规划器照样会把车往那里派。车辆靠自己的"激光雷达"发现它：
 * <pre>
 *  每辆车 10 Hz 扫描：48 束射线、360°、量程 3 m，对真实世界做光线投射（建筑、障碍、路障、其他车和行人）
 *     │
 *     ├─ 命中点分类：落在已知建筑/路障上 → 已知静态物；靠近已知的车 / 行人 → 动态目标；其余 → 未知障碍
 *     └─ 占据栅格（log-odds，限制在 [−2, 4]）：未知障碍所在格 +0.85，射线经过的格 −0.4
 *  某格的占据概率超过 0.8 → 服务端把它加入全局地图（等同路障），收回经过它的行驶授权，相关车辆立即重规划
 *  被感知加入的障碍格，之后被观测为空闲（概率低于 0.2）→ 自动从地图上撤掉，道路恢复通行
 * </pre>
 * 外部设备（Matlab 等）驱动的车同样参与：服务端按设备上报的位姿模拟它的雷达扫描（相当于 Gazebo
 * 里的传感器插件），用于建图，并以 scan 消息下发给设备做本地安全检查。手机拍照识别到的路障
 * 也作为观测并入同一张占据栅格（见 {@link #observeObstacle}）。
 */
public final class Perception {
    /** 每次扫描的射线数（360° 均分）。 */
    public static final int RAYS = 48;
    /** 量程（米）。 */
    public static final double RANGE = 3.0;
    /** 内部扫描频率（Hz）。 */
    public static final double SCAN_HZ = 10;

    static final double L_HIT = 0.85;
    static final double L_FREE = -0.4;
    static final double L_MAX = 4.0;
    /** 空闲证据的下限：限制得比上限紧，新出现的障碍 3 次命中（0.3 s）就能推翻"这里一直是空的"。 */
    static final double L_MIN = -2.0;
    static final double L_CONFIRM = 1.4;    // p ≈ 0.80
    static final double L_CLEAR = -1.4;     // p ≈ 0.20
    /** 命中点离已知智能体中心多近算"看到的是它"（米，加在半径上）。 */
    static final double AGENT_MATCH = 0.15;

    /** 一次扫描的结果。 */
    public static final class Scan {
        /** 各射线距离（米），未命中为 RANGE。射线 k 的角度 = theta − π + k·2π/RAYS。 */
        public final double[] ranges = new double[RAYS];
        /** 命中类型：0 无，1 建筑/边界，2 障碍/路障，3 车辆，4 行人。 */
        public final byte[] kinds = new byte[RAYS];
        /** 射线命中的格子（光线投射直接给出；命中车辆、行人或未命中时为 −1）。 */
        final int[] cells = new int[RAYS];
        public double x, y, theta, t;
        public int vehicles, pedestrians, unknown;
    }

    private final GridMap map;
    /** 真实世界中存在、系统可能还不知道的障碍。 */
    final boolean[] hidden;
    private final double[] hiddenSince;
    /** 由图像识别登记到真实世界里的障碍的到期时间（NaN 表示不是图像识别登记的）。 */
    private final double[] visionUntil;
    private final List<Integer> visionCells = new ArrayList<>();
    /** 占据栅格（log-odds），建筑格不使用。 */
    final double[] logOdds;
    /** 由感知加入全局地图的障碍（之后可被感知自动撤掉）。 */
    final boolean[] discovered;
    private final double[] lastSeen;
    private final int[] stamp;
    private int stampSeq;
    private final int drivableCells;
    private final double radiusTaxi;
    private final double radiusPhone;
    private final Map<Agent, Scan> scans = new IdentityHashMap<>();
    private double scanTimer;
    private long scanCount;

    // ---- 统计
    int discoveries;
    int clears;
    double sumDiscoverySec;
    int timedDiscoveries;
    int reports;

    Perception(GridMap map, double taxiRadius, double phoneRadius) {
        this.map = map;
        this.radiusTaxi = taxiRadius;
        this.radiusPhone = phoneRadius;
        int n = map.size();
        hidden = new boolean[n];
        hiddenSince = new double[n];
        visionUntil = new double[n];
        java.util.Arrays.fill(visionUntil, Double.NaN);
        logOdds = new double[n];
        discovered = new boolean[n];
        lastSeen = new double[n];
        java.util.Arrays.fill(lastSeen, Double.NEGATIVE_INFINITY);
        stamp = new int[n];
        int d = 0;
        for (int i = 0; i < n; i++) if (map.type(i).drivable()) d++;
        drivableCells = d;
    }

    // ================================================================ 场景接口

    /** 场景脚本放置 / 移除真实世界中的障碍（系统并不会因此直接知道）。 */
    public void setHidden(World w, int cell, boolean on) {
        if (!map.type(cell).drivable()) return;
        visionUntil[cell] = Double.NaN;   // 场景脚本接管这一格
        if (hidden[cell] == on) return;
        hidden[cell] = on;
        hiddenSince[cell] = w.time();
        String at = "(" + map.cx(cell) + "," + map.cy(cell) + ")";
        if (on) w.events().info(w.time(), "真实世界 " + at + " 出现障碍（系统尚不知道，等待车辆感知）");
        else w.events().info(w.time(), "真实世界 " + at + " 的障碍已移除（等待车辆感知后更新地图）");
    }

    /** 忘掉某格的感知结果（场景循环重新开始时用）：撤掉由感知加入的障碍，清空置信度。 */
    void forget(World w, int cell) {
        logOdds[cell] = 0;
        if (discovered[cell]) {
            discovered[cell] = false;
            if (map.isBlocked(cell)) w.applyBlock(cell, false);
        }
    }

    /**
     * 人工设置 / 解除路障时调用：这一格从此由"人工路障"管理（系统已知），
     * 清掉感知对它的结论，避免同一格被重复计入"感知发现的障碍"。
     */
    void manual(int cell) {
        discovered[cell] = false;
        logOdds[cell] = 0;
    }

    // ================================================================ 每个仿真步

    void update(World w, double dt) {
        scanTimer += dt;
        if (scanTimer < 1.0 / SCAN_HZ - 1e-9) return;
        scanTimer = 0;
        scanCount++;
        expireVisionObstacles(w);
        List<Agent> agents = w.agents();
        for (Agent a : new ArrayList<>(agents)) {
            if (!a.taxi) continue;
            Scan s = scan(a, agents, w.time());
            scans.put(a, s);
            integrate(w, a, s, agents);
            if (!a.internal() && scanCount % 2 == 0) w.devices().sendScan(a, s);
        }
        scans.keySet().retainAll(new HashSet<>(agents));
    }

    /** 一辆车的激光雷达扫描（对真实世界做光线投射）。 */
    Scan scan(Agent a, List<Agent> agents, double t) {
        Scan s = new Scan();
        s.x = a.x;
        s.y = a.y;
        s.theta = a.theta;
        s.t = t;
        Set<Agent> seen = new HashSet<>();
        for (int k = 0; k < RAYS; k++) {
            double ang = a.theta - Math.PI + k * 2 * Math.PI / RAYS;
            double dx = Math.cos(ang), dy = Math.sin(ang);
            double[] wall = castWall(a.x, a.y, dx, dy);
            double r = wall[0];
            byte kind = (byte) wall[1];
            int cell = (int) wall[2];
            Agent hitAgent = null;
            for (Agent o : agents) {
                if (o == a) continue;
                double rr = o.taxi ? radiusTaxi : radiusPhone;
                double tHit = rayCircle(a.x, a.y, dx, dy, o.x, o.y, rr);
                if (tHit >= 0 && tHit < r) {
                    r = tHit;
                    hitAgent = o;
                }
            }
            if (hitAgent != null) {
                kind = (byte) (hitAgent.taxi ? 3 : 4);
                cell = -1;
                seen.add(hitAgent);
            }
            s.ranges[k] = Math.min(r, RANGE);
            s.kinds[k] = r >= RANGE ? 0 : kind;
            s.cells[k] = r >= RANGE ? -1 : cell;
        }
        for (Agent o : seen) {
            if (o.taxi) s.vehicles++;
            else s.pedestrians++;
        }
        return s;
    }

    /** 真实世界中挡住射线的东西：地图外、建筑、未知障碍、人工路障（感知加入的格子只是"认知"，不算实体）。 */
    boolean solid(int cell) {
        return !map.type(cell).drivable() || hidden[cell] || (map.isBlocked(cell) && !discovered[cell]);
    }

    /**
     * 栅格光线投射（Amanatides–Woo）：返回 {距离, 类型, 命中的格子}，类型 1 = 建筑/边界，2 = 障碍/路障，
     * 0 = 未命中；命中地图边界或未命中时格子为 −1。
     */
    double[] castWall(double x0, double y0, double dx, double dy) {
        double res = map.resolution();
        int cx = (int) Math.floor(x0 / res), cy = (int) Math.floor(y0 / res);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1;
        double tMaxX = dx != 0 ? ((cx + (dx > 0 ? 1 : 0)) * res - x0) / dx : Double.POSITIVE_INFINITY;
        double tMaxY = dy != 0 ? ((cy + (dy > 0 ? 1 : 0)) * res - y0) / dy : Double.POSITIVE_INFINITY;
        double tDeltaX = dx != 0 ? res / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? res / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double t = 0;
        while (t <= RANGE) {
            if (!map.inBounds(cx, cy)) return new double[]{t, 1, -1};
            int c = map.index(cx, cy);
            if (t > 0 && solid(c)) return new double[]{t, map.type(c).drivable() ? 2 : 1, c};
            if (tMaxX < tMaxY) {
                t = tMaxX;
                tMaxX += tDeltaX;
                cx += stepX;
            } else {
                t = tMaxY;
                tMaxY += tDeltaY;
                cy += stepY;
            }
        }
        return new double[]{RANGE, 0, -1};
    }

    /** 射线与圆的第一个交点距离；不相交返回 −1。 */
    static double rayCircle(double x0, double y0, double dx, double dy, double cx, double cy, double r) {
        double fx = cx - x0, fy = cy - y0;
        double along = fx * dx + fy * dy;
        double perp2 = fx * fx + fy * fy - along * along;
        if (perp2 > r * r) return -1;
        double half = Math.sqrt(r * r - perp2);
        double t = along - half;
        if (t < 0) t = along + half > 0 ? 0 : -1;
        return t;
    }

    // ================================================================ 建图

    /**
     * 把一次扫描并入占据栅格。命中点按"系统已知的信息"分类（而不是偷看真值）：
     * 已知建筑 / 路障 → 不更新；离某个已知智能体很近 → 动态目标，不更新；其余 → 未知障碍。
     *
     * <p>命中点所在的格子直接取光线投射时撞上的那一格。不能用"命中点再往前一点"去反查格子：
     * 射线擦过建筑的角时，往前 2 cm 可能已经出了建筑格，落进旁边的路面，被误当成障碍。</p>
     */
    private void integrate(World w, Agent a, Scan s, List<Agent> agents) {
        stampSeq++;
        double now = w.time();
        int unknownHits = 0;
        List<Integer> confirm = new ArrayList<>();
        List<Integer> cleared = new ArrayList<>();
        for (int k = 0; k < RAYS; k++) {
            double ang = s.theta - Math.PI + k * 2 * Math.PI / RAYS;
            double dx = Math.cos(ang), dy = Math.sin(ang);
            double r = s.ranges[k];
            boolean hit = s.kinds[k] != 0;
            // 射线经过的格子：空闲观测（命中点前 5 cm 为止）
            double free = hit ? Math.max(0, r - 0.05) : r;
            markFree(a, s.x, s.y, dx, dy, free, now, cleared);
            if (!hit) continue;
            double hx = s.x + dx * r, hy = s.y + dy * r;
            if (nearKnownAgent(a, hx, hy, agents)) continue;             // 动态目标（已知的车 / 行人）
            int c = s.cells[k];
            if (c < 0 || !map.type(c).drivable()) continue;            // 已知建筑 / 边界
            if (map.isBlocked(c) && !discovered[c]) continue;           // 已知的人工路障
            lastSeen[c] = now;
            if (stamp[c] == stampSeq) continue;
            stamp[c] = stampSeq;
            if (!discovered[c]) unknownHits++;
            logOdds[c] = Math.min(L_MAX, logOdds[c] + L_HIT);
            if (!discovered[c] && logOdds[c] >= L_CONFIRM) confirm.add(c);
        }
        s.unknown = unknownHits;
        for (int c : confirm) discover(w, c, a.id, "激光雷达");
        for (int c : cleared) {
            if (discovered[c] && logOdds[c] <= L_CLEAR) clear(w, c, a.id);
        }
    }

    private void markFree(Agent a, double x0, double y0, double dx, double dy, double dist, double now,
                          List<Integer> cleared) {
        double res = map.resolution();
        int cx = (int) Math.floor(x0 / res), cy = (int) Math.floor(y0 / res);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1;
        double tMaxX = dx != 0 ? ((cx + (dx > 0 ? 1 : 0)) * res - x0) / dx : Double.POSITIVE_INFINITY;
        double tMaxY = dy != 0 ? ((cy + (dy > 0 ? 1 : 0)) * res - y0) / dy : Double.POSITIVE_INFINITY;
        double tDeltaX = dx != 0 ? res / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? res / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double t = 0;
        while (t < dist) {
            if (!map.inBounds(cx, cy)) return;
            int c = map.index(cx, cy);
            if (!map.type(c).drivable()) return;
            double exit = Math.min(tMaxX, tMaxY);
            // 只有射线在格子里走过足够长（≥ 0.25 格）才算"看清楚这一格是空的"
            if (Math.min(exit, dist) - t >= 0.25 * res && stamp[c] != stampSeq) {
                stamp[c] = stampSeq;
                lastSeen[c] = now;
                logOdds[c] = Math.max(L_MIN, logOdds[c] + L_FREE);
                if (discovered[c] && logOdds[c] <= L_CLEAR) cleared.add(c);
            }
            if (tMaxX < tMaxY) {
                t = tMaxX;
                tMaxX += tDeltaX;
                cx += stepX;
            } else {
                t = tMaxY;
                tMaxY += tDeltaY;
                cy += stepY;
            }
        }
    }

    private boolean nearKnownAgent(Agent self, double x, double y, List<Agent> agents) {
        for (Agent o : agents) {
            if (o == self) continue;
            double r = (o.taxi ? radiusTaxi : radiusPhone) + AGENT_MATCH;
            if (Math.hypot(o.x - x, o.y - y) <= r) return true;
        }
        return false;
    }

    /**
     * 其他来源的障碍观测（例如手机拍照经视觉模块识别出路障），并入同一张占据栅格。
     *
     * @param weight 相当于几次激光命中
     */
    public void observeObstacle(World w, int cell, double weight, String source) {
        if (cell < 0 || !map.type(cell).drivable()) return;
        reports++;
        lastSeen[cell] = w.time();
        // 照片是"现在这里有东西"的直接证据：以前观测到的空闲不再作数
        logOdds[cell] = Math.min(L_MAX, Math.max(0, logOdds[cell]) + L_HIT * weight);
        if (!discovered[cell] && !map.isBlocked(cell) && logOdds[cell] >= L_CONFIRM) discover(w, cell, source, "图像识别");
    }

    /**
     * 手机拍照经图像识别发现的路障：既是一次观测（并入占据栅格），也说明真实世界里确实有它，
     * 所以同时登记到数字孪生的"真实世界"里，车辆的激光雷达随后也能看到它，前后一致。
     * 登记有有效期：ttl 秒内没有再被拍到，就认为它已经被搬走，从真实世界移除（地图上的障碍随后由感知撤掉）。
     */
    public void visionObstacle(World w, int cell, double weight, String source, double ttl) {
        if (cell < 0 || !map.type(cell).drivable()) return;
        boolean nearAgent = false;
        for (Agent a : w.agents()) {
            if (Math.hypot(a.x - map.centerX(cell), a.y - map.centerY(cell)) < 1.0 * map.resolution()) nearAgent = true;
        }
        if (!hidden[cell] && !nearAgent) {
            hidden[cell] = true;
            hiddenSince[cell] = w.time();
            visionUntil[cell] = w.time() + ttl;
            visionCells.add(cell);
        } else if (!Double.isNaN(visionUntil[cell])) {
            visionUntil[cell] = w.time() + ttl;
        }
        observeObstacle(w, cell, weight, source);
    }

    private void expireVisionObstacles(World w) {
        if (visionCells.isEmpty()) return;
        for (int i = visionCells.size() - 1; i >= 0; i--) {
            int c = visionCells.get(i);
            if (Double.isNaN(visionUntil[c])) {
                visionCells.remove(i);
            } else if (w.time() > visionUntil[c]) {
                visionUntil[c] = Double.NaN;
                visionCells.remove(i);
                if (hidden[c]) {
                    hidden[c] = false;
                    w.events().info(w.time(), "图像识别登记的障碍 (" + map.cx(c) + "," + map.cy(c)
                            + ") 一段时间内没有再被拍到，视为已移除（等车辆感知确认后从地图上撤掉）");
                }
            }
        }
    }

    private void discover(World w, int cell, String by, String how) {
        if (map.isBlocked(cell)) {
            discovered[cell] = false;
            return;
        }
        discovered[cell] = true;
        discoveries++;
        String latency = "";
        if (hidden[cell] && Double.isNaN(visionUntil[cell])) {   // 图像识别登记的障碍是"拍到即发现"，不计入发现延迟
            double d = w.time() - hiddenSince[cell];
            sumDiscoverySec += d;
            timedDiscoveries++;
            latency = "（障碍出现 " + Num.r1(d) + " s 后）";
        }
        w.events().warn(w.time(), by + " 通过" + how + "发现未知障碍 (" + map.cx(cell) + "," + map.cy(cell) + ")" + latency
                + "，已加入全局地图，相关车辆重新规划");
        w.applyBlock(cell, true);
    }

    private void clear(World w, int cell, String by) {
        discovered[cell] = false;
        clears++;
        w.events().info(w.time(), by + " 观测到 (" + map.cx(cell) + "," + map.cy(cell) + ") 的障碍已消失，地图已更新，道路恢复通行");
        w.applyBlock(cell, false);
    }

    // ================================================================ 输出

    Scan lastScan(Agent a) {
        return scans.get(a);
    }

    /** 尚未被感知的真实障碍格（[x0,y0,x1,y1,...]，用于界面"上帝视角"显示）。 */
    int[] undiscoveredCells() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < hidden.length; i++) {
            if (hidden[i] && !map.isBlocked(i)) out.add(i);
        }
        return flat(out);
    }

    int[] discoveredCells() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < discovered.length; i++) if (discovered[i]) out.add(i);
        return flat(out);
    }

    /** 有一定把握是障碍、但还没确认的格子（占据概率 0.6~0.8）。 */
    int[] suspectCells() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < logOdds.length; i++) {
            if (!discovered[i] && !map.isBlocked(i) && logOdds[i] > 0.4 && logOdds[i] < L_CONFIRM) out.add(i);
        }
        return flat(out);
    }

    private int[] flat(List<Integer> cells) {
        int[] out = new int[cells.size() * 2];
        for (int i = 0; i < cells.size(); i++) {
            out[i * 2] = map.cx(cells.get(i));
            out[i * 2 + 1] = map.cy(cells.get(i));
        }
        return out;
    }

    /** 最近 window 秒内被观测过的可行驶格比例（"地图核实覆盖率"）。 */
    double coverage(double now, double window) {
        int n = 0;
        for (int i = 0; i < lastSeen.length; i++) {
            if (map.type(i).drivable() && now - lastSeen[i] <= window) n++;
        }
        return drivableCells == 0 ? 0 : (double) n / drivableCells;
    }

    double avgDiscoverySec() {
        return timedDiscoveries == 0 ? 0 : sumDiscoverySec / timedDiscoveries;
    }

    boolean isHidden(int cell) {
        return hidden[cell];
    }

    long scanCount() {
        return scanCount;
    }
}
