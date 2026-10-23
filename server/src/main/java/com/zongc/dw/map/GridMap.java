package com.zongc.dw.map;

import java.util.ArrayList;
import java.util.List;

/**
 * 栅格地图：静态障碍（建筑）、车道方向，以及运行中可动态设置的路障。
 *
 * <p>格子索引 idx = y * width + x，(0,0) 是西南角。格 (x,y) 覆盖的世界坐标范围为
 * [x*res, (x+1)*res] × [y*res, (y+1)*res]，单位米。</p>
 */
public final class GridMap {
    private final String name;
    private final int width;
    private final int height;
    private final double res;
    private final CellType[] cells;
    private final boolean[] blocked;
    private final int[] laneCells;

    public GridMap(String name, int width, int height, double res, CellType[] cells) {
        if (cells.length != width * height) throw new IllegalArgumentException("格子数量与尺寸不符");
        this.name = name;
        this.width = width;
        this.height = height;
        this.res = res;
        this.cells = cells.clone();
        this.blocked = new boolean[cells.length];
        List<Integer> lanes = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            if (cells[i].isLane()) lanes.add(i);
        }
        this.laneCells = lanes.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---------- 基本查询 ----------

    public String name() { return name; }
    public int width() { return width; }
    public int height() { return height; }
    public double resolution() { return res; }
    public int size() { return cells.length; }

    public int index(int x, int y) { return y * width + x; }
    public int cx(int idx) { return idx % width; }
    public int cy(int idx) { return idx / width; }

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public CellType type(int idx) { return cells[idx]; }
    public boolean isCross(int idx) { return cells[idx] == CellType.CROSS; }
    public boolean isLane(int idx) { return cells[idx].isLane(); }
    public boolean isBlocked(int idx) { return blocked[idx]; }

    /** 可通行：不是建筑，也没有被设置路障。 */
    public boolean passable(int idx) {
        return cells[idx].drivable() && !blocked[idx];
    }

    public void setBlocked(int idx, boolean value) {
        if (!cells[idx].drivable()) return;
        blocked[idx] = value;
    }

    public int[] blockedCells() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < blocked.length; i++) {
            if (blocked[i]) list.add(i);
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 可作为上下车点、出生点的车道格（不含路口）。 */
    public int[] laneCells() { return laneCells; }

    // ---------- 通行规则 ----------

    /**
     * 从 from 格沿方向 d 移动一格。规则：
     * 车道格只能沿车道方向驶出；进入车道格时行驶方向必须与车道方向一致；路口/开放区域不限方向。
     * 本方法不检查路障（路障由规划器单独判断）。
     *
     * @return 目标格索引；不允许时返回 -1
     */
    public int move(int from, Dir d) {
        CellType ft = cells[from];
        if (ft.laneDir != null && ft.laneDir != d) return -1;
        int nx = cx(from) + d.dx;
        int ny = cy(from) + d.dy;
        if (!inBounds(nx, ny)) return -1;
        int to = index(nx, ny);
        CellType tt = cells[to];
        if (!tt.drivable()) return -1;
        if (tt.laneDir != null && tt.laneDir != d) return -1;
        return to;
    }

    // ---------- 坐标换算 ----------

    public double centerX(int idx) { return (cx(idx) + 0.5) * res; }
    public double centerY(int idx) { return (cy(idx) + 0.5) * res; }

    /** 世界坐标所在的格子；在地图外返回 -1。 */
    public int cellAt(double wx, double wy) {
        int x = (int) Math.floor(wx / res);
        int y = (int) Math.floor(wy / res);
        return inBounds(x, y) ? index(x, y) : -1;
    }

    /** 与圆（车身）有重叠的所有格子（不含地图外）。 */
    public int[] cellsOverlappingCircle(double wx, double wy, double r) {
        int x0 = (int) Math.floor((wx - r) / res), x1 = (int) Math.floor((wx + r) / res);
        int y0 = (int) Math.floor((wy - r) / res), y1 = (int) Math.floor((wy + r) / res);
        int[] buf = new int[(x1 - x0 + 1) * (y1 - y0 + 1)];
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (!inBounds(x, y)) continue;
                if (distToCell(wx, wy, x, y) < r) buf[n++] = index(x, y);
            }
        }
        return java.util.Arrays.copyOf(buf, n);
    }

    /**
     * 点到最近"不可行驶区域"（建筑或地图外）的距离，只在 searchR 范围内搜索；
     * 范围内没有障碍时返回 Double.POSITIVE_INFINITY。
     */
    public double distanceToObstacle(double wx, double wy, double searchR) {
        int x0 = (int) Math.floor((wx - searchR) / res), x1 = (int) Math.floor((wx + searchR) / res);
        int y0 = (int) Math.floor((wy - searchR) / res), y1 = (int) Math.floor((wy + searchR) / res);
        double best = Double.POSITIVE_INFINITY;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                boolean obstacle = !inBounds(x, y) || !cells[index(x, y)].drivable();
                if (obstacle) best = Math.min(best, distToCell(wx, wy, x, y));
            }
        }
        return best;
    }

    private double distToCell(double wx, double wy, int x, int y) {
        double minX = x * res, maxX = (x + 1) * res, minY = y * res, maxY = (y + 1) * res;
        double dx = Math.max(Math.max(minX - wx, 0), wx - maxX);
        double dy = Math.max(Math.max(minY - wy, 0), wy - maxY);
        return Math.hypot(dx, dy);
    }

    /** 按地图文件格式导出字符行（第一行是最北边一行）。 */
    public List<String> toRows() {
        List<String> rows = new ArrayList<>(height);
        for (int y = height - 1; y >= 0; y--) {
            StringBuilder sb = new StringBuilder(width);
            for (int x = 0; x < width; x++) sb.append(cells[index(x, y)].symbol);
            rows.add(sb.toString());
        }
        return rows;
    }
}
