package com.zongc.phone.core;

import java.util.List;
import java.util.Map;

/**
 * 数字世界的栅格地图（从 GET /api/map 获取）。
 *
 * <p>坐标约定与服务端一致：格子 (x, y) 覆盖 [x·res, (x+1)·res] × [y·res, (y+1)·res]，
 * (0, 0) 在西南角，y 向上（北）。接口返回的 rows 第一行是最北边一行，这里已经翻转过来。</p>
 *
 * <p>字符：'#' 建筑，'+' 路口，'.' 开放区域，'&gt;' '&lt;' '^' 'v' 单向车道。</p>
 */
public final class MapData {
    public final String name;
    public final int width;
    public final int height;
    public final double resolution;
    private final char[] cells;

    public MapData(String name, int width, int height, double resolution, char[] cells) {
        if (width <= 0 || height <= 0 || cells.length != width * height) {
            throw new IllegalArgumentException("地图尺寸与格子数不符");
        }
        if (!(resolution > 0)) throw new IllegalArgumentException("地图分辨率必须大于 0");
        this.name = name == null ? "" : name;
        this.width = width;
        this.height = height;
        this.resolution = resolution;
        this.cells = cells.clone();
    }

    /** 解析 /api/map 的 JSON。 */
    public static MapData fromJson(String json) {
        Map<String, Object> o = Json.parseObject(json);
        if (o == null) throw new IllegalArgumentException("地图数据不是 JSON 对象");
        int w = (int) Json.num(o, "width", -1);
        int h = (int) Json.num(o, "height", -1);
        double res = Json.num(o, "resolution", 1);
        List<Object> rows = Json.arr(o.get("rows"));
        if (w <= 0 || h <= 0 || rows == null || rows.size() != h) {
            throw new IllegalArgumentException("地图数据缺少 width / height / rows");
        }
        char[] cells = new char[w * h];
        for (int r = 0; r < h; r++) {
            Object row = rows.get(r);
            if (!(row instanceof String) || ((String) row).length() != w) {
                throw new IllegalArgumentException("地图第 " + (r + 1) + " 行长度不对");
            }
            String s = (String) row;
            int y = h - 1 - r;                      // 第一行是最北边
            for (int x = 0; x < w; x++) cells[y * w + x] = s.charAt(x);
        }
        return new MapData(Json.str(o, "name"), w, h, res, cells);
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    /** 格子字符；超出地图按建筑处理。 */
    public char at(int x, int y) {
        return inBounds(x, y) ? cells[y * width + x] : '#';
    }

    public boolean isBuilding(int x, int y) {
        return at(x, y) == '#';
    }

    public boolean isCross(int x, int y) {
        return at(x, y) == '+';
    }

    public boolean isLane(int x, int y) {
        char c = at(x, y);
        return c == '>' || c == '<' || c == '^' || c == 'v';
    }

    public double worldWidth() {
        return width * resolution;
    }

    public double worldHeight() {
        return height * resolution;
    }

    public int cellX(double wx) {
        return (int) Math.floor(wx / resolution);
    }

    public int cellY(double wy) {
        return (int) Math.floor(wy / resolution);
    }

    /** 把世界坐标限制在地图范围内。 */
    public double[] clamp(double wx, double wy) {
        return new double[]{Geo.clamp(wx, 0, worldWidth()), Geo.clamp(wy, 0, worldHeight())};
    }

    /**
     * 离 (wx, wy) 最近的车道格（可作为上下车点），跳过路障格。
     *
     * @param blocked 路障格坐标，扁平数组 [x0, y0, x1, y1, ...]，可以为 null
     * @return {x, y}；地图上没有车道时返回 null
     */
    public int[] nearestLane(double wx, double wy, int[] blocked) {
        int[] best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!isLane(x, y) || isBlocked(x, y, blocked)) continue;
                double dx = (x + 0.5) * resolution - wx, dy = (y + 0.5) * resolution - wy;
                double d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    private static boolean isBlocked(int x, int y, int[] blocked) {
        if (blocked == null) return false;
        for (int i = 0; i + 1 < blocked.length; i += 2) {
            if (blocked[i] == x && blocked[i + 1] == y) return true;
        }
        return false;
    }
}
