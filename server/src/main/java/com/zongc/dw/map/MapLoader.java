package com.zongc.dw.map;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 读取地图 JSON 文件（格式见 docs/protocol.md 第 5 节）。 */
public final class MapLoader {

    /** 地图文件结构。 */
    static final class MapFile {
        String name;
        double resolution = 1.0;
        List<String> rows;
    }

    private MapLoader() {}

    public static GridMap load(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return parse(json, file.getFileName().toString());
    }

    public static GridMap parse(String json, String fallbackName) {
        MapFile mf;
        try {
            mf = new Gson().fromJson(json, MapFile.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("地图文件不是合法的 JSON: " + e.getMessage(), e);
        }
        if (mf == null || mf.rows == null || mf.rows.isEmpty()) {
            throw new IllegalArgumentException("地图文件缺少 rows");
        }
        int height = mf.rows.size();
        int width = mf.rows.get(0).length();
        CellType[] cells = new CellType[width * height];
        for (int r = 0; r < height; r++) {
            String row = mf.rows.get(r);
            if (row.length() != width) {
                throw new IllegalArgumentException("地图第 " + (r + 1) + " 行长度为 " + row.length() + "，应为 " + width);
            }
            int y = height - 1 - r;   // rows 第一行是最北边
            for (int x = 0; x < width; x++) {
                cells[y * width + x] = CellType.fromSymbol(row.charAt(x));
            }
        }
        if (mf.resolution <= 0) throw new IllegalArgumentException("resolution 必须为正数");
        String name = mf.name != null ? mf.name : fallbackName;
        GridMap map = new GridMap(name, width, height, mf.resolution, cells);
        List<String> problems = validate(map);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("地图存在死路：" + String.join("；", problems));
        }
        return map;
    }

    /** 检查每个车道格沿车道方向都能继续行驶（没有死胡同）。 */
    public static List<String> validate(GridMap map) {
        List<String> problems = new ArrayList<>();
        for (int idx = 0; idx < map.size(); idx++) {
            CellType t = map.type(idx);
            if (t.isLane() && map.move(idx, t.laneDir) < 0) {
                problems.add("(" + map.cx(idx) + "," + map.cy(idx) + ")");
                if (problems.size() >= 10) break;
            }
        }
        return problems;
    }
}
