package com.zongc.dw.world;

import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;

import java.util.Random;

/**
 * 随机订单生成：泊松过程，平均每分钟 ratePerMin 单。
 * 每个仿真步以概率 1 − e^(−λ·dt) 产生一单，起终点从车道格中随机抽取。
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

    int[] randomTrip(World w) {
        int[] lanes = map.laneCells();
        for (int tries = 0; tries < 50; tries++) {
            int a = lanes[rng.nextInt(lanes.length)];
            int b = lanes[rng.nextInt(lanes.length)];
            if (!map.passable(a) || !map.passable(b)) continue;
            int dist = Math.abs(map.cx(a) - map.cx(b)) + Math.abs(map.cy(a) - map.cy(b));
            if (dist < tc.minTripCells) continue;
            if (w.isPickupTaken(a)) continue;
            return new int[]{a, b};
        }
        return null;
    }
}
