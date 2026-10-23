package com.zongc.dw.world;

import com.google.gson.JsonParser;
import com.zongc.dw.TestSupport;
import com.zongc.dw.config.Config;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 需求热点与空车调度（rebalancing）。 */
public class RebalanceTest {

    private static void run(World w, double seconds) {
        long steps = Math.round(seconds / w.dt());
        for (long i = 0; i < steps; i++) w.step();
    }

    /** 订单起点按热点权重分布：热点附近的订单比例接近 originShare。 */
    @Test
    public void ordersFollowDemandHotspots() throws Exception {
        Config cfg = TestSupport.config();
        cfg.tasks.ratePerMin = 0;
        World w = TestSupport.world(cfg);
        w.setDemand(Demand.fromJson(JsonParser.parseString("{\"originShare\":0.8,\"destShare\":0,\"hotspots\":["
                + "{\"name\":\"居民区\",\"center\":[5,4],\"radius\":5}]}").getAsJsonObject(), w.map()));
        int near = 0, n = 0;
        for (int i = 0; i < 2000; i++) {
            int[] trip = w.randomTrip();
            if (trip == null) continue;
            n++;
            if (Math.hypot(w.map().cx(trip[0]) - 5, w.map().cy(trip[0]) - 4) <= 5) near++;
        }
        double share = (double) near / n;
        assertTrue("热点出发的比例 " + share, share > 0.7 && share < 0.9);
    }

    /** 开启调度后，空车被派往需求高的区域（并在那里巡游）。 */
    @Test
    public void idleTaxisAreSentTowardsHotspots() throws Exception {
        Config cfg = TestSupport.config();
        cfg.tasks.ratePerMin = 4;
        cfg.dispatch.rebalance = true;
        World w = TestSupport.world(cfg);
        w.setDemand(Demand.fromJson(JsonParser.parseString("{\"originShare\":0.9,\"destShare\":0,\"hotspots\":["
                + "{\"name\":\"居民区\",\"center\":[5,4],\"radius\":5}]}").getAsJsonObject(), w.map()));
        run(w, 6 * 60);
        Rebalancer.View v = w.snapshot(false, 1).zones();
        assertTrue("应发生空车调度", v.moves() > 0);
        int hot = 0;
        for (int z = 0; z < v.perMin().length; z++) if (v.perMin()[z] > v.perMin()[hot]) hot = z;
        assertEquals("需求最高的区域应是居民区所在区域（西南角）", 0, hot);
        assertEquals(0, w.metrics().collisions);
    }

    /** 潮汐需求下（同样的订单序列），开启空车调度后乘客平均等待时间明显缩短。 */
    @Test
    public void rebalancingShortensWaitingUnderTidalDemand() throws Exception {
        double off = 0, on = 0;
        for (long seed = 1; seed <= 4; seed++) {
            for (boolean reb : new boolean[]{false, true}) {
                Config cfg = TestSupport.config();
                cfg.seed = seed;
                cfg.tasks.ratePerMin = 4;
                cfg.dispatch.rebalance = reb;
                World w = TestSupport.world(cfg);
                w.setScenario(Scenario.load(Path.of("scenarios/rush-hour.json"), w.map()));
                run(w, 20 * 60);
                Metrics.View m = w.metricsView();
                assertEquals(0, m.collisions());
                if (reb) on += m.avgWait();
                else off += m.avgWait();
            }
        }
        assertTrue("调度开 " + on / 4 + " s，关 " + off / 4 + " s", on < off * 0.92);
    }
}
