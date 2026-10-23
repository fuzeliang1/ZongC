package com.zongc.dw.world;

import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;

import java.util.Random;

/**
 * 随机订单生成：泊松过程，平均每分钟 ratePerMin 单。
 * 每个仿真步以概率 1 − e^(−λ·dt) 产生一单。起终点按当前需求模型（{@link Demand}）抽取：
 * 默认在全城车道格上均匀分布，设置了需求热点时偏向热点。
 */
final class TaskGenerator {
    private final Config.Tasks tc;
    private final GridMap map;
    private final Random rng;

    TaskGenerator(Config.Tasks tc, GridMap map, Random rng) {
        this.tc = tc;
        this.map = map;
        this.rng = rng;
    }

    void update(World w, double dt) {
        if (tc.ratePerMin <= 0) return;
        double p = 1 - Math.exp(-tc.ratePerMin / 60.0 * dt);
        if (rng.nextDouble() >= p) return;
        if (w.waitingCount() >= tc.maxWaiting) return;
        int[] trip = randomTrip(w);
        if (trip != null) w.createTask(trip[0], trip[1]);
    }

    /**
     * 抽一个订单的起终点。要求：都可通行；真实世界里不在未知障碍上（乘客不会站在施工区里叫车）；
     * 起终点相距足够远；上车点没有被别的订单占用。
     */
    int[] randomTrip(World w) {
        Demand d = w.demand();
        Perception p = w.perception();
        for (int tries = 0; tries < 80; tries++) {
            int a = d.sampleOrigin(map, rng);
            int b = d.sampleDest(map, rng);
            if (!map.passable(a) || !map.passable(b) || p.isHidden(a) || p.isHidden(b)) continue;
            int dist = Math.abs(map.cx(a) - map.cx(b)) + Math.abs(map.cy(a) - map.cy(b));
            if (dist < tc.minTripCells) continue;
            if (w.isPickupTaken(a)) continue;
            return new int[]{a, b};
        }
        return null;
    }
}
