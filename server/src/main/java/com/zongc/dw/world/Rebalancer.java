package com.zongc.dw.world;

import com.zongc.dw.dispatch.Hungarian;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.plan.DistanceField;
import com.zongc.dw.util.Num;

import java.util.ArrayList;
import java.util.List;

/**
 * 空车调度（rebalancing）：把空车提前调往"即将有人叫车"的区域，缩短乘客等待时间。
 *
 * <ol>
 *   <li><b>需求预测</b>：把城市划成若干区域（默认 9×9 格一块），每来一个订单，就给上车点所在区域的
 *       需求计数 +1，计数按时间常数 τ 指数衰减 —— 得到各区域"最近的叫车强度" λ_z；</li>
 *   <li><b>目标分布</b>：空车总数 I 按 λ_z 的比例分到各区域：target_z = I·λ_z / Σλ；</li>
 *   <li><b>最优调度</b>：多余空车（所在区域 supply &gt; target）调往缺车区域（supply &lt; target），
 *       用匈牙利算法求"总空驶距离最小"的指派，代价 = 沿路网（遵守车道方向）的实际距离；</li>
 *   <li>调过去的车在该区域内<b>就近巡游</b>，不再满城乱跑；接到订单后恢复正常。</li>
 * </ol>
 * 关闭调度时（默认），空车随机巡游。两种方式可以用 Experiment 工具在同样的需求下对比。
 */
public final class Rebalancer {
    private final GridMap map;
    private final int zoneSize;
    private final int cols;
    private final int rows;
    private final double tau;
    private final double[] demand;
    private final List<List<Integer>> zoneLanes = new ArrayList<>();
    private final int[] anchor;
    private double timer;
    private double lastDecayAt;
    int moves;

    Rebalancer(GridMap map, int zoneSize, double tauSec) {
        this.map = map;
        this.zoneSize = Math.max(3, zoneSize);
        this.cols = (map.width() + this.zoneSize - 1) / this.zoneSize;
        this.rows = (map.height() + this.zoneSize - 1) / this.zoneSize;
        this.tau = Math.max(10, tauSec);
        this.demand = new double[cols * rows];
        this.anchor = new int[cols * rows];
        for (int z = 0; z < cols * rows; z++) zoneLanes.add(new ArrayList<>());
        for (int c : map.laneCells()) zoneLanes.get(zoneOf(c)).add(c);
        for (int z = 0; z < cols * rows; z++) {
            // 锚点：离区域中心最近的车道格
            double cx = (z % cols + 0.5) * this.zoneSize, cy = (z / cols + 0.5) * this.zoneSize;
            int best = -1;
            double bestD = Double.POSITIVE_INFINITY;
            for (int c : zoneLanes.get(z)) {
                double d = Math.hypot(map.cx(c) + 0.5 - cx, map.cy(c) + 0.5 - cy);
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            anchor[z] = best;
        }
        java.util.Arrays.fill(demand, 0.05);   // 先验：各区域都有一点需求
    }

    int zoneOf(int cell) {
        int zx = Math.min(cols - 1, map.cx(cell) / zoneSize), zy = Math.min(rows - 1, map.cy(cell) / zoneSize);
        return zy * cols + zx;
    }

    int zoneAt(double x, double y) {
        int c = map.cellAt(x, y);
        if (c < 0) {
            int zx = (int) Math.max(0, Math.min(cols - 1, Math.floor(x / map.resolution() / zoneSize)));
            int zy = (int) Math.max(0, Math.min(rows - 1, Math.floor(y / map.resolution() / zoneSize)));
            return zy * cols + zx;
        }
        return zoneOf(c);
    }

    /** 记录一个新订单（无论调度是否开启都记录，用于界面上的需求热力图）。 */
    void onTask(int pickup, double now) {
        decay(now);
        demand[zoneOf(pickup)] += 1;
    }

    private void decay(double now) {
        double dt = now - lastDecayAt;
        if (dt <= 0) return;
        double k = Math.exp(-dt / tau);
        for (int z = 0; z < demand.length; z++) demand[z] = 0.05 + (demand[z] - 0.05) * k;
        lastDecayAt = now;
    }

    /** 周期性调度（只在开启时由 World 调用）。 */
    void update(World w, double dt, double periodSec) {
        timer += dt;
        if (timer < periodSec) return;
        timer = 0;
        decay(w.time());

        List<Agent> idle = new ArrayList<>();
        for (Agent a : w.agents()) {
            if (a.taxi && a.status == AgentStatus.CRUISING && a.task == null && !a.replanPending && a.route != null) {
                idle.add(a);
            }
        }
        if (idle.isEmpty()) return;
        int zones = demand.length;
        double total = 0;
        for (int z = 0; z < zones; z++) if (anchor[z] >= 0) total += demand[z];
        if (total <= 0) return;

        // 当前供给：空车所在（或正在前往）的区域
        int[] supply = new int[zones];
        int[] zoneOfCar = new int[idle.size()];
        for (int i = 0; i < idle.size(); i++) {
            Agent a = idle.get(i);
            zoneOfCar[i] = a.homeZone >= 0 ? a.homeZone : zoneAt(a.x, a.y);
            supply[zoneOfCar[i]]++;
        }
        // 目标供给（最大余数法取整）
        int n = idle.size();
        int[] target = new int[zones];
        double[] frac = new double[zones];
        int assigned = 0;
        for (int z = 0; z < zones; z++) {
            if (anchor[z] < 0) continue;
            double share = n * demand[z] / total;
            target[z] = (int) Math.floor(share);
            frac[z] = share - target[z];
            assigned += target[z];
        }
        while (assigned < n) {
            int best = -1;
            for (int z = 0; z < zones; z++) {
                if (anchor[z] >= 0 && (best < 0 || frac[z] > frac[best])) best = z;
            }
            if (best < 0) break;
            target[best]++;
            frac[best] = -1;
            assigned++;
        }

        // 缺车的"空位"和多余的车
        List<Integer> slots = new ArrayList<>();
        for (int z = 0; z < zones; z++) for (int k = supply[z]; k < target[z]; k++) slots.add(z);
        List<Integer> movers = new ArrayList<>();
        int[] surplus = new int[zones];
        for (int z = 0; z < zones; z++) surplus[z] = supply[z] - target[z];
        for (int i = 0; i < idle.size(); i++) {
            int z = zoneOfCar[i];
            if (surplus[z] > 0) {
                movers.add(i);
                surplus[z]--;
            }
        }
        if (slots.isEmpty() || movers.isEmpty()) return;

        // 代价：沿路网到目标区域锚点的距离（每个目标区域算一次距离场）
        double[][] cost = new double[movers.size()][slots.size()];
        java.util.Map<Integer, int[]> fields = new java.util.HashMap<>();
        for (int j = 0; j < slots.size(); j++) {
            int z = slots.get(j);
            int[] field = fields.computeIfAbsent(z, zz -> DistanceField.toGoal(map, anchor[zz]));
            for (int i = 0; i < movers.size(); i++) {
                Agent a = idle.get(movers.get(i));
                int from = a.route.cell(Math.max(a.authority, a.progress));
                int d = field[from];
                cost[i][j] = d < 0 ? 1e6 : d * map.resolution();
            }
        }
        int[] match = Hungarian.solve(cost);
        for (int i = 0; i < match.length; i++) {
            int j = match[i];
            if (j < 0 || cost[i][j] >= 1e6) continue;
            Agent a = idle.get(movers.get(i));
            int z = slots.get(j);
            if (w.cruiseTo(a, randomLane(w, z), z)) {
                moves++;
                w.events().info(w.time(), "空车调度：" + a.id + " 前往需求较高的区域 " + zoneName(z)
                        + "（约 " + Math.round(cost[i][j]) + " m）");
            }
        }
    }

    /** 区域内随机一个可通行车道格（巡游目的地）。 */
    int randomLane(World w, int zone) {
        List<Integer> lanes = zoneLanes.get(zone);
        for (int tries = 0; tries < 20 && !lanes.isEmpty(); tries++) {
            int c = lanes.get(w.rng().nextInt(lanes.size()));
            if (map.passable(c)) return c;
        }
        return anchor[zone];
    }

    boolean hasLanes(int zone) {
        return zone >= 0 && zone < zoneLanes.size() && !zoneLanes.get(zone).isEmpty();
    }

    String zoneName(int z) {
        int x0 = (z % cols) * zoneSize, y0 = (z / cols) * zoneSize;
        return "(" + x0 + "~" + Math.min(map.width() - 1, x0 + zoneSize - 1) + ", "
                + y0 + "~" + Math.min(map.height() - 1, y0 + zoneSize - 1) + ")";
    }

    /** 推送给前端的需求热力图：区域大小、行列数、各区域预测需求（单/分钟，按行从南到北）。 */
    public record View(int zoneSize, int cols, int rows, double[] perMin, boolean on, int moves) {}

    View view(double now, boolean on) {
        decay(now);
        double[] perMin = new double[demand.length];
        for (int z = 0; z < demand.length; z++) perMin[z] = Num.r2(Math.max(0, demand[z] - 0.05) * 60 / tau);
        return new View(zoneSize, cols, rows, perMin, on, moves);
    }
}
