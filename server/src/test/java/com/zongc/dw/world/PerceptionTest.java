package com.zongc.dw.world;

import com.zongc.dw.TestSupport;
import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 感知与建图：模拟激光雷达、未知障碍的发现与撤除、误检、掉头脱困、整个感知场景的安全性。 */
public class PerceptionTest {

    private static void run(World w, double seconds) {
        long steps = Math.round(seconds / w.dt());
        for (long i = 0; i < steps; i++) w.step();
    }

    /** 射线距离和类型与几何一致：前方的车、北边的建筑、南边的地图边界。 */
    @Test
    public void lidarMeasuresBuildingsVehiclesAndBoundary() throws Exception {
        Config cfg = TestSupport.config();
        cfg.taxis = 2;
        cfg.tasks.ratePerMin = 0;
        World w = TestSupport.world(cfg);
        Agent a = w.agents().get(0), b = w.agents().get(1);
        a.x = 5.5; a.y = 0.5; a.theta = 0;      // (5,0) 向东车道
        b.x = 7.5; b.y = 0.5; b.theta = 0;      // 正前方 2 m
        Perception.Scan s = w.perception().scan(a, w.agents(), 0);
        // 射线 k 的角度 = θ − π + k·2π/48：k = 24 正前方，k = 36 左侧（北），k = 12 右侧（南）
        assertEquals("前方车辆距离 = 2 − 车身半径", 2 - cfg.vehicle.bodyRadius, s.ranges[24], 1e-6);
        assertEquals(3, s.kinds[24]);
        assertEquals("北边 (5,2) 是建筑，边界在 y = 2", 1.5, s.ranges[36], 1e-6);
        assertEquals(1, s.kinds[36]);
        assertEquals("南边是地图边界 y = 0", 0.5, s.ranges[12], 1e-6);
        assertEquals(1, s.vehicles);
        // 后方（k = 0）沿向东车道往西看：3 m 内没有东西
        assertEquals(Perception.RANGE, s.ranges[0], 1e-9);
        assertEquals(0, s.kinds[0]);
    }

    /** 找一辆在行驶的内部车，返回它路线前方 3~5 格内、离所有智能体都足够远的一个车道格。 */
    private static int cellAhead(World w) {
        GridMap map = w.map();
        for (Agent a : w.agents()) {
            if (!a.internal() || a.route == null || a.v < 0.5) continue;
            for (int k = a.progress + 3; k <= Math.min(a.route.last(), a.progress + 5); k++) {
                int c = a.route.cell(k);
                if (!map.isLane(c) || !map.passable(c)) continue;
                boolean clear = true;
                for (Agent o : w.agents()) {
                    if (Math.hypot(o.x - map.centerX(c), o.y - map.centerY(c)) < 2.6) clear = false;
                }
                if (clear) return c;
            }
        }
        return -1;
    }

    /** 系统不知道的障碍出现在车辆前方：车辆发现它、写进全局地图、在它前面停下或绕开，绝不撞上。 */
    @Test
    public void hiddenObstacleAheadIsDiscoveredAndAvoided() throws Exception {
        int trials = 0;
        for (long seed = 1; seed <= 8 && trials < 4; seed++) {
            Config cfg = TestSupport.config();
            cfg.seed = seed;
            World w = TestSupport.world(cfg);
            run(w, 10);
            int cell = -1;
            for (int i = 0; i < 40 && cell < 0; i++) {
                cell = cellAhead(w);
                if (cell < 0) run(w, 0.5);
            }
            if (cell < 0) continue;
            trials++;
            w.perception().setHidden(w, cell, true);
            double t0 = w.time();
            while (!w.perception().discovered[cell] && w.time() - t0 < 5) w.step();
            assertTrue("前方 3~5 格的障碍应在几秒内被发现", w.perception().discovered[cell]);
            assertFalse("发现后应写入全局地图", w.map().passable(cell));
            run(w, 30);
            assertEquals("车撞障碍", 0, w.metrics().barrierHits);
            assertEquals("车车碰撞", 0, w.metrics().collisions);
            for (Agent a : w.agents()) {
                if (a.route != null) {
                    assertTrue(a.id + " 的路线仍经过障碍", a.route.indexOf(cell, a.progress, a.route.last()) < 0);
                }
            }
            assertTrue("应记录发现延迟", w.perception().avgDiscoverySec() < 5);
        }
        assertTrue("应至少完成 3 次试验", trials >= 3);
    }

    /** 障碍撤走以后，车辆路过时观测到那里空了，系统自动把它从地图上删掉，道路恢复通行。 */
    @Test
    public void removedObstacleIsClearedFromTheMap() throws Exception {
        Config cfg = TestSupport.config();
        cfg.taxis = 16;
        cfg.tasks.ratePerMin = 12;
        World w = TestSupport.world(cfg);
        int cell = w.map().index(16, 15);
        w.perception().setHidden(w, cell, true);
        run(w, 240);
        assertTrue("繁忙道路上的障碍应在 4 分钟内被发现", w.perception().discovered[cell]);
        w.perception().setHidden(w, cell, false);
        double t0 = w.time();
        while (w.perception().discovered[cell] && w.time() - t0 < 300) w.step();
        assertFalse("障碍撤走后应被感知撤掉", w.perception().discovered[cell]);
        assertTrue(w.map().passable(cell));
        assertTrue(w.perception().clears >= 1);
        assertEquals(0, w.metrics().barrierHits);
        assertEquals(0, w.metrics().collisions);
    }

    /** 没有任何障碍时稠密交通运行 10 分钟：不应"看见"不存在的障碍（车辆、建筑转角都不能被误判）。 */
    @Test
    public void noFalseDiscoveriesInDenseTraffic() throws Exception {
        Config cfg = TestSupport.config();
        cfg.taxis = 16;
        cfg.tasks.ratePerMin = 14;
        World w = TestSupport.world(cfg);
        run(w, 600);
        assertEquals("误检", 0, w.perception().discoveries);
        assertTrue("地图核实覆盖率应较高", w.perception().coverage(w.time(), 60) > 0.6);
    }

    /** 车开进一段单行道后前面被封死、目的地也被切断：原地掉头，目的地改到附近，不会一直卡住。 */
    @Test
    public void carTurnsAroundWhenItsLaneIsCutOff() throws Exception {
        Config cfg = TestSupport.config();
        cfg.taxis = 1;
        cfg.tasks.ratePerMin = 0;
        World w = TestSupport.world(cfg);
        GridMap map = w.map();
        Agent a = w.agents().get(0);
        // 把车放到向东车道 (13,24)，订单从 (14,24) 到 (20,24)，都在 x = 12..21 这段单行道上
        a.route = null;
        a.x = map.centerX(map.index(13, 24));
        a.y = map.centerY(map.index(13, 24));
        a.theta = 0;
        a.s = 0;
        a.v = 0;
        assertTrue(w.planRoute(a, map.index(35, 8), Purpose.CRUISE, null));
        Task t = w.createTask(map.index(15, 24), map.index(20, 24));
        run(w, 25);
        assertTrue("车应已接到乘客", t.state == Task.State.PICKED);
        // 乘客上车后前方 (17,24) 被封：目的地 (20,24) 被切断
        int block = map.index(17, 24);
        if (map.cellAt(a.x, a.y) == block) run(w, 2);
        w.setRoadblock(block, true);
        run(w, 90);
        assertTrue("应借对向车道掉头", w.metrics().uturns >= 1);
        assertEquals("订单应送达（改在附近下车）", Task.State.DELIVERED, t.state);
        assertTrue("最长受阻不应超过绕行阈值", w.metrics().maxBlocked <= cfg.traffic.stuckRerouteSec + 1);
        assertEquals(0, w.metrics().barrierHits);
    }

    /** 感知场景完整跑几轮（障碍陆续出现、消失）：零碰撞、不卡死、障碍都被发现。 */
    @Test
    public void perceptionScenarioIsSafe() throws Exception {
        for (long seed = 1; seed <= 3; seed++) {
            Config cfg = TestSupport.config();
            cfg.seed = seed;
            World w = TestSupport.world(cfg);
            Scenario s = Scenario.load(Path.of("scenarios/perception.json"), w.map());
            w.setScenario(s);
            run(w, 15 * 60);
            Metrics m = w.metrics();
            String tag = "seed " + seed + " ";
            assertEquals(tag + "车车碰撞", 0, m.collisions);
            assertEquals(tag + "车墙碰撞", 0, m.obstacleHits);
            assertEquals(tag + "车撞障碍", 0, m.barrierHits);
            assertTrue(tag + "最长受阻 " + m.maxBlocked, m.maxBlocked <= cfg.traffic.stuckRerouteSec + 1);
            assertTrue(tag + "发现次数 " + w.perception().discoveries, w.perception().discoveries >= 4);
            Snapshot snap = w.snapshot(false, 1);
            assertNotNull(snap.scenario());
            assertTrue(snap.metrics().coverage() > 0.5);
        }
    }
}
