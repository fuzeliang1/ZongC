package com.zongc.dw.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zongc.dw.map.GridMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 订单需求模型。
 *
 * <p>默认起终点在全城车道格上均匀随机。可以设置若干<b>热点</b>（地铁站、学校、商场……）：
 * 订单起点以概率 originShare 落在某个热点附近（按 origin 权重选热点），终点以概率 destShare
 * 落在热点附近（按 dest 权重选）。用它模拟早晚高峰的潮汐需求，检验空车调度（rebalancing）的效果。</p>
 *
 * <pre>
 * {"action": "demand", "originShare": 0.7, "destShare": 0.3, "hotspots": [
 *     {"name": "地铁站", "center": [5, 8], "radius": 4, "origin": 3, "dest": 1},
 *     {"name": "学校",   "center": [45, 25], "radius": 4, "origin": 1, "dest": 3}]}
 * </pre>
 */
public final class Demand {
    /** 一个需求热点。 */
    public record Hotspot(String name, int x, int y, double radius, double origin, double dest, int[] lanes) {}

    public final List<Hotspot> hotspots;
    public final double originShare;
    public final double destShare;

    Demand(List<Hotspot> hotspots, double originShare, double destShare) {
        this.hotspots = Collections.unmodifiableList(hotspots);
        this.originShare = originShare;
        this.destShare = destShare;
    }

    static Demand uniform() {
        return new Demand(new ArrayList<>(), 0, 0);
    }

    public boolean isUniform() {
        return hotspots.isEmpty() || (originShare <= 0 && destShare <= 0);
    }

    static Demand fromJson(JsonObject o, GridMap map) {
        double os = o.has("originShare") ? o.get("originShare").getAsDouble() : 0.7;
        double ds = o.has("destShare") ? o.get("destShare").getAsDouble() : 0.3;
        List<Hotspot> list = new ArrayList<>();
        if (o.has("hotspots")) {
            for (JsonElement e : o.getAsJsonArray("hotspots")) {
                JsonObject h = e.getAsJsonObject();
                int x = h.getAsJsonArray("center").get(0).getAsInt();
                int y = h.getAsJsonArray("center").get(1).getAsInt();
                double r = h.has("radius") ? h.get("radius").getAsDouble() : 4;
                double ow = h.has("origin") ? h.get("origin").getAsDouble() : 1;
                double dw = h.has("dest") ? h.get("dest").getAsDouble() : 1;
                String name = h.has("name") ? h.get("name").getAsString() : "热点" + (list.size() + 1);
                int[] lanes = lanesNear(map, x, y, r);
                if (lanes.length == 0) throw new IllegalArgumentException("热点 " + name + " 附近没有车道格");
                list.add(new Hotspot(name, x, y, r, Math.max(0, ow), Math.max(0, dw), lanes));
            }
        }
        return new Demand(list, clamp01(os), clamp01(ds));
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    static int[] lanesNear(GridMap map, int x, int y, double r) {
        List<Integer> out = new ArrayList<>();
        for (int c : map.laneCells()) {
            if (Math.hypot(map.cx(c) - x, map.cy(c) - y) <= r) out.add(c);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 抽一个起点车道格（可能不可通行，由调用方检查）。 */
    int sampleOrigin(GridMap map, Random rng) {
        return sample(map, rng, originShare, true);
    }

    int sampleDest(GridMap map, Random rng) {
        return sample(map, rng, destShare, false);
    }

    private int sample(GridMap map, Random rng, double share, boolean origin) {
        int[] all = map.laneCells();
        if (hotspots.isEmpty() || rng.nextDouble() >= share) return all[rng.nextInt(all.length)];
        double total = 0;
        for (Hotspot h : hotspots) total += origin ? h.origin() : h.dest();
        if (total <= 0) return all[rng.nextInt(all.length)];
        double u = rng.nextDouble() * total;
        for (Hotspot h : hotspots) {
            u -= origin ? h.origin() : h.dest();
            if (u <= 0) return h.lanes()[rng.nextInt(h.lanes().length)];
        }
        Hotspot last = hotspots.get(hotspots.size() - 1);
        return last.lanes()[rng.nextInt(last.lanes().length)];
    }

    /** 推送给前端的热点（用于在地图上画出需求热点）。 */
    public record View(String name, int x, int y, double radius, double origin, double dest) {}

    List<View> views() {
        List<View> out = new ArrayList<>();
        for (Hotspot h : hotspots) out.add(new View(h.name(), h.x(), h.y(), h.radius(), h.origin(), h.dest()));
        return out;
    }
}
