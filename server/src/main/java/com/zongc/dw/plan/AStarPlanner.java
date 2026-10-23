package com.zongc.dw.plan;

import com.zongc.dw.map.Dir;
import com.zongc.dw.map.GridMap;

import java.util.Arrays;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 带车道方向约束的 A* 路径规划。
 *
 * <p>搜索状态 = (格子, 驶入该格的方向)，这样可以：</p>
 * <ol>
 *   <li>给转弯加代价，得到更平顺、更符合实际的路线；</li>
 *   <li>禁止原地掉头（车辆不能瞬间反向），掉头只能在路口用两次转弯完成。</li>
 * </ol>
 * <p>代价 = 行驶距离（米）+ 转弯代价 + 可选的额外惩罚（用于绕开拥堵或受阻格）。
 * 启发函数用曼哈顿距离，它不会高估真实代价，因此 A* 得到的是最优解。</p>
 */
public final class AStarPlanner {
    private static final int NO_DIR = 4;

    private final GridMap map;
    private final double turnCost;

    public AStarPlanner(GridMap map, double turnCost) {
        this.map = map;
        this.turnCost = turnCost;
    }

    private record Node(double f, int state) {}

    /**
     * 规划从 start 到 goal 的路径。
     *
     * @param start   起点格
     * @param heading 进入起点格时的行驶方向，未知时传 null
     * @param goal    终点格
     * @param penalty 额外代价（格索引 → 米），可为 null
     * @return 格序列（含起点和终点）；不可达时返回 null
     */
    public int[] plan(int start, Dir heading, int goal, Map<Integer, Double> penalty) {
        if (start == goal) return new int[]{start};
        if (!map.passable(goal)) return null;

        final int n = map.size();
        final double res = map.resolution();
        final double[] g = new double[n * 5];
        final int[] parent = new int[n * 5];
        final boolean[] closed = new boolean[n * 5];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);

        final int gx = map.cx(goal), gy = map.cy(goal);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        int s0 = start * 5 + (heading == null ? NO_DIR : heading.ordinal());
        g[s0] = 0;
        open.add(new Node(heuristic(start, gx, gy, res), s0));
        Dir[] dirs = Dir.values();

        while (!open.isEmpty()) {
            Node node = open.poll();
            int s = node.state;
            if (closed[s]) continue;
            closed[s] = true;
            int cell = s / 5;
            int din = s % 5;
            if (cell == goal) return reconstruct(parent, s);

            for (Dir d : dirs) {
                if (din != NO_DIR && d == dirs[din].opposite()) continue;   // 禁止原地掉头
                int to = map.move(cell, d);
                if (to < 0 || !map.passable(to)) continue;
                double cost = res;
                if (din != NO_DIR && d.ordinal() != din) cost += turnCost;
                if (penalty != null) cost += penalty.getOrDefault(to, 0.0);
                int ns = to * 5 + d.ordinal();
                double ng = g[s] + cost;
                if (ng < g[ns]) {
                    g[ns] = ng;
                    parent[ns] = s;
                    open.add(new Node(ng + heuristic(to, gx, gy, res), ns));
                }
            }
        }
        return null;
    }

    private double heuristic(int cell, int gx, int gy, double res) {
        return res * (Math.abs(map.cx(cell) - gx) + Math.abs(map.cy(cell) - gy));
    }

    private static int[] reconstruct(int[] parent, int goalState) {
        int len = 0;
        for (int s = goalState; s >= 0; s = parent[s]) len++;
        int[] path = new int[len];
        int i = len - 1;
        for (int s = goalState; s >= 0; s = parent[s]) path[i--] = s / 5;
        return path;
    }
}
