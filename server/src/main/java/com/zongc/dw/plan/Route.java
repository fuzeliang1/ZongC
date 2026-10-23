package com.zongc.dw.plan;

import com.zongc.dw.map.Dir;
import com.zongc.dw.map.GridMap;

/**
 * 一条行驶路线：格子序列 + 平滑后的几何形状。
 *
 * <p>几何构造：路线在每个格子里走一"段"。直行段是穿过格子中心的直线；
 * 90° 转弯段用半径为半格的四分之一圆弧连接两条边的中点，圆弧完全落在转弯格内，
 * 所以车辆中心不会切到建筑。第一段从格子中心出发（或从入口边中点出发，见 entryDir），
 * 最后一段在格子中心结束。</p>
 *
 * <p>弧长 s 从路线起点算起。第 i 段覆盖弧长区间 [sStart(i), sEnd(i)]，正好对应
 * "车辆中心位于第 i 个格子"的那一段，因此 sEnd(i) 就是驶出第 i 格的边界位置。</p>
 *
 * <p>外部设备（Matlab 等）收到的是这条平滑路线的等距采样点（{@link #devicePolyline()}），
 * 停车位置用"沿采样折线的累计长度"表达（{@link #chordAt(double)}），与内部仿真车完全一致。</p>
 */
public final class Route {
    private final int[] cells;
    private final Dir entryDir;
    private final Dir[] dirIn;
    private final double res;
    private final double h;
    private final double[] sStart, sEnd;
    private final boolean[] arc;
    private final int[] turn;
    private final double[] ax, ay, bx, by;
    private final double[] ox, oy, phi0;
    private final double[] lineHeading;
    private final double[] centerX, centerY;
    private final double length;

    /**
     * @param cells    相邻格子组成的序列，至少 1 个
     * @param entryDir 车辆驶入第一个格子的方向；为 null 时路线从第一个格子中心出发
     */
    public Route(GridMap map, int[] cells, Dir entryDir) {
        if (cells == null || cells.length == 0) throw new IllegalArgumentException("路线不能为空");
        int n = cells.length;
        this.cells = cells.clone();
        this.entryDir = entryDir;
        this.res = map.resolution();
        this.h = res / 2;
        dirIn = new Dir[n];
        sStart = new double[n];
        sEnd = new double[n];
        arc = new boolean[n];
        turn = new int[n];
        ax = new double[n]; ay = new double[n]; bx = new double[n]; by = new double[n];
        ox = new double[n]; oy = new double[n]; phi0 = new double[n];
        lineHeading = new double[n];
        centerX = new double[n]; centerY = new double[n];

        dirIn[0] = entryDir;
        for (int i = 1; i < n; i++) {
            Dir d = Dir.fromDelta(map.cx(cells[i]) - map.cx(cells[i - 1]), map.cy(cells[i]) - map.cy(cells[i - 1]));
            if (d == null) throw new IllegalArgumentException("路线中第 " + i + " 格与前一格不相邻");
            dirIn[i] = d;
        }

        double acc = 0;
        for (int i = 0; i < n; i++) {
            double px = map.centerX(cells[i]), py = map.centerY(cells[i]);
            centerX[i] = px;
            centerY[i] = py;
            Dir din = dirIn[i];
            Dir dout = i < n - 1 ? dirIn[i + 1] : null;
            ax[i] = din == null ? px : px - din.dx * h;
            ay[i] = din == null ? py : py - din.dy * h;
            bx[i] = dout == null ? px : px + dout.dx * h;
            by[i] = dout == null ? py : py + dout.dy * h;
            double len;
            if (din != null && dout != null && din != dout) {
                if (din == dout.opposite()) throw new IllegalArgumentException("路线包含原地掉头");
                arc[i] = true;
                turn[i] = din.isLeftTurnTo(dout) ? 1 : -1;
                ox[i] = ax[i] + dout.dx * h;      // 圆心：入口点沿出口方向偏移半格
                oy[i] = ay[i] + dout.dy * h;
                phi0[i] = Math.atan2(ay[i] - oy[i], ax[i] - ox[i]);
                len = Math.PI / 2 * h;
            } else {
                len = Math.hypot(bx[i] - ax[i], by[i] - ay[i]);
                Dir hd = dout != null ? dout : din;
                lineHeading[i] = hd != null ? hd.angle() : Double.NaN;
            }
            sStart[i] = acc;
            acc += len;
            sEnd[i] = acc;
        }
        length = acc;
    }

    // ---------- 基本信息 ----------

    public int size() { return cells.length; }
    public int last() { return cells.length - 1; }
    public int cell(int i) { return cells[i]; }
    public int[] cells() { return cells.clone(); }
    public Dir entryDir() { return entryDir; }
    /** 驶入第 i 格时的方向（第 0 格为 entryDir，可能为 null）。 */
    public Dir dirInto(int i) { return dirIn[i]; }
    public double length() { return length; }
    public double sStart(int i) { return sStart[i]; }
    public double sEnd(int i) { return sEnd[i]; }
    public boolean isArc(int i) { return arc[i]; }
    public double centerX(int i) { return centerX[i]; }
    public double centerY(int i) { return centerY[i]; }

    /** 在 [from, to] 范围内查找格子，找不到返回 -1。 */
    public int indexOf(int cell, int from, int to) {
        for (int i = Math.max(0, from); i <= Math.min(to, last()); i++) {
            if (cells[i] == cell) return i;
        }
        return -1;
    }

    /** 弧长 s 落在哪一段（即车辆中心在第几个格子）。 */
    public int pieceAt(double s) {
        int lo = 0, hi = cells.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (sStart[mid] <= s) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    // ---------- 几何 ----------

    /** 弧长 s 处的位姿。 */
    public Pose poseAt(double s) {
        s = Math.max(0, Math.min(length, s));
        int i = pieceAt(s);
        double u = s - sStart[i];
        if (arc[i]) {
            double ang = phi0[i] + turn[i] * (u / h);
            return new Pose(ox[i] + h * Math.cos(ang), oy[i] + h * Math.sin(ang),
                    normalize(ang + turn[i] * Math.PI / 2));
        }
        double len = sEnd[i] - sStart[i];
        if (len < 1e-12) return new Pose(ax[i], ay[i], lineHeading[i]);
        double t = u / len;
        return new Pose(ax[i] + (bx[i] - ax[i]) * t, ay[i] + (by[i] - ay[i]) * t, lineHeading[i]);
    }

    /** 点 (x,y) 在第 from..to 段上的最近点对应的弧长。 */
    public double project(double x, double y, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(last(), to);
        double bestS = sStart[from], bestD = Double.POSITIVE_INFINITY;
        for (int i = from; i <= to; i++) {
            double[] candidates;
            double len = sEnd[i] - sStart[i];
            if (arc[i]) {
                double delta = normalize(Math.atan2(y - oy[i], x - ox[i]) - phi0[i]) * turn[i];
                double u = Math.max(0, Math.min(Math.PI / 2, delta)) * h;
                candidates = new double[]{sStart[i] + u, sStart[i], sEnd[i]};
            } else if (len < 1e-12) {
                candidates = new double[]{sStart[i]};
            } else {
                double t = ((x - ax[i]) * (bx[i] - ax[i]) + (y - ay[i]) * (by[i] - ay[i])) / (len * len);
                candidates = new double[]{sStart[i] + Math.max(0, Math.min(1, t)) * len};
            }
            for (double s : candidates) {
                Pose p = poseAt(s);
                double d = Math.hypot(p.x() - x, p.y() - y);
                if (d < bestD) {
                    bestD = d;
                    bestS = s;
                }
            }
        }
        return bestS;
    }

    // ---------- 设备路点（平滑路线的等距采样） ----------

    private double[] sampleX, sampleY, sampleS, sampleChord;

    /**
     * 发给外部设备的路点：沿平滑路线每隔不超过 1/4 格采样一个点（转弯处沿圆弧采样）。
     * 设备用这些点做纯追踪即可得到与内部仿真车一致的轨迹，不会切弯撞到建筑。
     *
     * @return {x[], y[], s[], chord[]}：坐标、真实弧长、沿折线的累计长度
     */
    public synchronized double[][] devicePolyline() {
        if (sampleX == null) {
            double step = res / 4;
            java.util.List<double[]> pts = new java.util.ArrayList<>();
            for (int i = 0; i < cells.length; i++) {
                double len = sEnd[i] - sStart[i];
                int n = Math.max(1, (int) Math.ceil(len / step - 1e-9));
                if (len < 1e-9) n = 1;
                for (int k = 0; k < n; k++) {
                    double s = sStart[i] + len * k / n;
                    if (!pts.isEmpty() && s - pts.get(pts.size() - 1)[2] < 1e-9) continue;
                    Pose p = poseAt(s);
                    pts.add(new double[]{p.x(), p.y(), s});
                }
            }
            Pose end = poseAt(length);
            if (pts.isEmpty() || length - pts.get(pts.size() - 1)[2] > 1e-9) pts.add(new double[]{end.x(), end.y(), length});
            int n = pts.size();
            sampleX = new double[n];
            sampleY = new double[n];
            sampleS = new double[n];
            sampleChord = new double[n];
            for (int i = 0; i < n; i++) {
                sampleX[i] = pts.get(i)[0];
                sampleY[i] = pts.get(i)[1];
                sampleS[i] = pts.get(i)[2];
                if (i > 0) sampleChord[i] = sampleChord[i - 1] + Math.hypot(sampleX[i] - sampleX[i - 1], sampleY[i] - sampleY[i - 1]);
            }
        }
        return new double[][]{sampleX, sampleY, sampleS, sampleChord};
    }

    /** 真实弧长 s 对应的设备路点折线长度（用于 permit 消息中的 stopS）。 */
    public double chordAt(double s) {
        double[][] p = devicePolyline();
        double[] ss = p[2], cs = p[3];
        if (s <= 0) return 0;
        if (s >= ss[ss.length - 1]) return cs[cs.length - 1];
        int lo = 0, hi = ss.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ss[mid] <= s) lo = mid;
            else hi = mid;
        }
        double t = (s - ss[lo]) / (ss[hi] - ss[lo]);
        return cs[lo] + t * (cs[hi] - cs[lo]);
    }

    public static double normalize(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a <= -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
