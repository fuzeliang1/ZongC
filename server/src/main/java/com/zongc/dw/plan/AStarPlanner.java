package com.zongc.dw.plan;

import com.zongc.dw.map.CellType;
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

    /**
     * 紧急掉头：前方道路被堵死、按正常规则无路可走时（例如单行车道上出现障碍），
     * 借用左侧的对向车道掉头 —— 从 start 驶入左侧相邻格，再沿相反方向继续行驶。
     *
     * <p>几何上，"start 左转 90° + 左侧格再左转 90°"正好拼成一个半径半格的半圆，
     * 圆心是两格的公共角点，车身不会越出这两格（见 {@link Route}）。
     * 只有左侧格是与当前方向相反的车道（或路口 / 开放区域）时才允许。</p>
     *
     * @param start   当前路线的最后一格（车辆将在这一格里开始掉头）
     * @param heading 驶入 start 时的方向
     * @return 从左侧格开始（不含 start）到 goal 的格序列；无法掉头或掉头后仍不可达时返回 null
     */
    public int[] planUTurn(int start, Dir heading, int goal, Map<Integer, Double> penalty) {
        if (heading == null) return null;
        Dir left = heading.left();
        int sx = map.cx(start) + left.dx, sy = map.cy(start) + left.dy;
        if (!map.inBounds(sx, sy)) return null;
        int side = map.index(sx, sy);
        if (!map.passable(side)) return null;
        CellType t = map.type(side);
        if (t.laneDir != null && t.laneDir != heading.opposite()) return null;
        // 掉头后必须沿反方向驶出左侧格
        int nx = sx - heading.dx, ny = sy - heading.dy;
        if (!map.inBounds(nx, ny)) return null;
        int next = map.index(nx, ny);
        if (!map.passable(next) || map.move(side, heading.opposite()) != next) return null;
        int[] tail = plan(next, heading.opposite(), goal, penalty);
        if (tail == null) return null;
        int[] out = new int[tail.length + 1];
        out[0] = side;
        System.arraycopy(tail, 0, out, 1, tail.length);
        return out;
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
