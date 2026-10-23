package com.zongc.dw.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zongc.dw.map.GridMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 场景脚本：按仿真时间自动触发的事件序列（server/scenarios/*.json）。
 *
 * <p>PPT 要求"启动后即自主运行，考核过程中不可人工切换模式、进行设置或进行遥控"，
 * 所以演示中要出现的突发情况（路障、未知障碍、早晚高峰、订单潮……）都写进场景脚本，
 * 到时间自动发生。文件格式：</p>
 * <pre>
 * {
 *   "name": "期末演示",
 *   "loop": true, "loopGapSec": 20,
 *   "events": [
 *     {"t": 0,  "action": "announce", "text": "演示开始"},
 *     {"t": 30, "action": "obstacle", "cell": [17, 25], "text": "主干道出现施工（未知障碍）"},
 *     {"t": 90, "action": "obstacle", "cell": [17, 25], "on": false}
 *   ]
 * }
 * </pre>
 * 支持的动作见 {@link #ACTIONS} 与 docs/protocol.md 第 6 节。
 */
public final class Scenario {
    /** 支持的动作。 */
    public static final Set<String> ACTIONS = Set.of(
            "announce", "rate", "strategy", "roadblock", "obstacle", "task", "rush", "clear",
            "demand", "rebalance");

    /** 一个定时动作。 */
    public record Action(double t, String action, JsonObject args, String text) {
        public int[] cell(String key) {
            JsonArray a = args.getAsJsonArray(key);
            return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()};
        }

        public double num(String key, double def) {
            JsonElement e = args.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsDouble();
        }

        public boolean bool(String key, boolean def) {
            JsonElement e = args.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsBoolean();
        }

        public String str(String key) {
            JsonElement e = args.get(key);
            return e == null || e.isJsonNull() ? null : e.getAsString();
        }
    }

    public final String name;
    public final String description;
    public final boolean loop;
    public final double loopGapSec;
    public final List<Action> actions;

    public Scenario(String name, String description, boolean loop, double loopGapSec, List<Action> actions) {
        this.name = name;
        this.description = description;
        this.loop = loop;
        this.loopGapSec = loopGapSec;
        List<Action> sorted = new ArrayList<>(actions);
        sorted.sort((a, b) -> Double.compare(a.t(), b.t()));   // 稳定排序：同一时刻按文件中的先后
        this.actions = Collections.unmodifiableList(sorted);
    }

    /** 最后一个动作的时间。 */
    public double duration() {
        return actions.isEmpty() ? 0 : actions.get(actions.size() - 1).t();
    }

    public static Scenario load(Path file, GridMap map) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return parse(json, map);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("场景文件 " + file.getFileName() + "：" + e.getMessage(), e);
        }
    }

    /** 解析并检查场景（格子是否在地图内、可通行，字段是否齐全），出错时抛出带中文说明的异常。 */
    public static Scenario parse(String json, GridMap map) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不是合法的 JSON：" + e.getMessage());
        }
        if (!root.isJsonObject()) throw new IllegalArgumentException("顶层必须是 JSON 对象");
        JsonObject o = root.getAsJsonObject();
        String name = o.has("name") ? o.get("name").getAsString() : "未命名场景";
        String desc = o.has("description") ? o.get("description").getAsString() : "";
        boolean loop = o.has("loop") && o.get("loop").getAsBoolean();
        double gap = o.has("loopGapSec") ? o.get("loopGapSec").getAsDouble() : 20;
        if (!o.has("events") || !o.get("events").isJsonArray()) throw new IllegalArgumentException("缺少 events 数组");
        List<Action> list = new ArrayList<>();
        int i = 0;
        for (JsonElement e : o.getAsJsonArray("events")) {
            i++;
            if (!e.isJsonObject()) throw new IllegalArgumentException("第 " + i + " 个事件不是对象");
            JsonObject ev = e.getAsJsonObject();
            if (!ev.has("t") || !ev.has("action")) throw new IllegalArgumentException("第 " + i + " 个事件缺少 t 或 action");
            double t = ev.get("t").getAsDouble();
            String action = ev.get("action").getAsString();
            if (t < 0) throw new IllegalArgumentException("第 " + i + " 个事件的 t 不能为负");
            if (!ACTIONS.contains(action)) {
                throw new IllegalArgumentException("第 " + i + " 个事件：未知动作 " + action + "，可用：" + ACTIONS);
            }
            String text = ev.has("text") && !ev.get("text").isJsonNull() ? ev.get("text").getAsString() : null;
            Action a = new Action(t, action, ev, text);
            check(a, i, map);
            list.add(a);
        }
        return new Scenario(name, desc, loop, Math.max(0, gap), list);
    }

    private static void check(Action a, int i, GridMap map) {
        String where = "第 " + i + " 个事件（" + a.action() + "）：";
        switch (a.action()) {
            case "announce" -> {
                if (a.text() == null) throw new IllegalArgumentException(where + "缺少 text");
            }
            case "rate" -> {
                double r = a.num("ratePerMin", -1);
                if (r < 0 || r > 120) throw new IllegalArgumentException(where + "ratePerMin 应在 0..120");
            }
            case "strategy" -> {
                String s = a.str("value");
                if (!"GREEDY".equals(s) && !"HUNGARIAN".equals(s)) {
                    throw new IllegalArgumentException(where + "value 只能是 GREEDY 或 HUNGARIAN");
                }
            }
            case "roadblock", "obstacle" -> checkCell(a, "cell", where, map, false);
            case "task" -> {
                checkCell(a, "from", where, map, true);
                checkCell(a, "to", where, map, true);
            }
            case "rush" -> {
                double n = a.num("count", 0);
                if (n < 1 || n > 50) throw new IllegalArgumentException(where + "count 应在 1..50");
            }
            case "demand" -> {
                if (!a.args().has("hotspots") || !a.args().get("hotspots").isJsonArray()) {
                    throw new IllegalArgumentException(where + "缺少 hotspots 数组");
                }
                for (JsonElement h : a.args().getAsJsonArray("hotspots")) {
                    JsonObject ho = h.getAsJsonObject();
                    JsonArray c = ho.getAsJsonArray("center");
                    if (c == null || c.size() != 2 || !map.inBounds(c.get(0).getAsInt(), c.get(1).getAsInt())) {
                        throw new IllegalArgumentException(where + "热点的 center 应为地图内的 [x, y]");
                    }
                }
            }
            default -> {
                // announce/clear/rebalance：无额外字段要求
            }
        }
    }

    private static void checkCell(Action a, String key, String where, GridMap map, boolean lane) {
        JsonElement e = a.args().get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 2) {
            throw new IllegalArgumentException(where + key + " 应为 [x, y] 格子坐标");
        }
        int[] c = a.cell(key);
        if (!map.inBounds(c[0], c[1])) throw new IllegalArgumentException(where + key + " 超出地图范围");
        int idx = map.index(c[0], c[1]);
        if (!map.type(idx).drivable()) throw new IllegalArgumentException(where + key + " 是建筑格");
        if (lane && !map.isLane(idx)) throw new IllegalArgumentException(where + key + " 必须是车道格（上下车点）");
    }
}
