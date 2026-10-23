package com.zongc.dw;

import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.world.Agent;
import com.zongc.dw.world.Scenario;
import com.zongc.dw.world.Snapshot;
import com.zongc.dw.world.Task;
import com.zongc.dw.world.World;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 场景脚本：文件校验、按时触发、循环播放时恢复世界、未知障碍避开车辆。 */
public class ScenarioTest {

    private static void runUntil(World w, double t) {
        while (w.time() < t - 1e-9) w.step();
    }

    @Test
    public void shippedScenariosAreValid() throws Exception {
        GridMap map = TestSupport.map(TestSupport.config());
        List<Path> files;
        try (var s = Files.list(Path.of("scenarios"))) {
            files = s.filter(p -> p.toString().endsWith(".json")).toList();
        }
        assertTrue("至少应有 3 个场景脚本", files.size() >= 3);
        for (Path f : files) {
            Scenario sc = Scenario.load(f, map);
            assertFalse(f + " 没有事件", sc.actions.isEmpty());
            assertFalse(f + " 缺少说明", sc.description.isBlank());
        }
    }

    @Test
    public void invalidScenariosAreRejectedWithReasons() throws Exception {
        GridMap map = TestSupport.map(TestSupport.config());
        expectError(map, "{\"events\":[{\"t\":1,\"action\":\"fly\"}]}", "未知动作");
        expectError(map, "{\"events\":[{\"t\":1,\"action\":\"roadblock\",\"cell\":[5,5]}]}", "建筑格");
        expectError(map, "{\"events\":[{\"t\":1,\"action\":\"roadblock\",\"cell\":[99,5]}]}", "超出地图");
        expectError(map, "{\"events\":[{\"t\":1,\"action\":\"task\",\"from\":[0,0],\"to\":[5,0]}]}", "车道格");
        expectError(map, "{\"events\":[{\"action\":\"announce\",\"text\":\"x\"}]}", "缺少 t");
        expectError(map, "{\"events\":[{\"t\":1,\"action\":\"rate\",\"ratePerMin\":500}]}", "ratePerMin");
        expectError(map, "not json", "JSON");
    }

    private static void expectError(GridMap map, String json, String keyword) {
        try {
            Scenario.parse(json, map);
            fail("应当拒绝: " + json);
        } catch (IllegalArgumentException e) {
            assertTrue("错误信息应包含\"" + keyword + "\"：" + e.getMessage(), e.getMessage().contains(keyword));
        }
    }

    @Test
    public void eventsFireOnTimeAndLoopRestoresTheWorld() throws Exception {
        Config cfg = TestSupport.config();
        cfg.taxis = 5;
        World w = TestSupport.world(cfg);
        GridMap map = w.map();
        Scenario s = Scenario.parse("{\"name\":\"测试\",\"loop\":true,\"loopGapSec\":2,\"events\":["
                + "{\"t\":1,\"action\":\"rate\",\"ratePerMin\":0},"
                + "{\"t\":2,\"action\":\"roadblock\",\"cell\":[5,8]},"
                + "{\"t\":3,\"action\":\"task\",\"from\":[3,24],\"to\":[35,15]},"
                + "{\"t\":4,\"action\":\"announce\",\"text\":\"你好\"}]}", map);
        w.setScenario(s);
        runUntil(w, 1.5);
        assertEquals(0.0, cfg.tasks.ratePerMin, 0);
        runUntil(w, 2.5);
        assertFalse("t = 2 设置的路障", map.passable(map.index(5, 8)));
        runUntil(w, 3.5);
        boolean found = false;
        for (Task t : w.activeTasks()) {
            if (t.pickup == map.index(3, 24) && t.dropoff == map.index(35, 15)) found = true;
        }
        assertTrue("t = 3 创建的订单", found);
        runUntil(w, 4.5);
        Snapshot snap = w.snapshot(false, 1);
        assertNotNull(snap.scenario());
        assertEquals("你好", snap.scenario().banner());
        assertEquals(4, snap.scenario().step());
        runUntil(w, 6.6);   // 最后一个事件 t = 4，间隔 2 s 后第二轮开始
        snap = w.snapshot(false, 1);
        assertEquals(2, snap.scenario().loop());
        assertTrue("新一轮开始时撤掉上一轮的路障", map.passable(map.index(5, 8)));
    }

    @Test
    public void obstaclesWaitUntilNoAgentIsNearby() throws Exception {
        Config cfg = TestSupport.config();
        World w = TestSupport.world(cfg);
        runUntil(w, 2);
        Agent car = null;
        for (Agent a : w.agents()) {
            if (a.taxi && a.v > 0.5) car = a;
        }
        assertNotNull(car);
        int cell = w.map().cellAt(car.x, car.y);
        int cx = w.map().cx(cell), cy = w.map().cy(cell);
        w.setScenario(Scenario.parse("{\"events\":[{\"t\":0,\"action\":\"obstacle\",\"cell\":[" + cx + "," + cy + "]}]}",
                w.map()));
        runUntil(w, w.time() + 0.3);
        assertFalse("车就在这一格上，障碍应推迟出现", w.snapshot(false, 1).hidden().length > 0);
        runUntil(w, w.time() + 25);
        boolean appeared = w.snapshot(false, 1).hidden().length > 0 || w.snapshot(false, 1).discovered().length > 0;
        assertTrue("车开走后障碍应出现", appeared);
        assertEquals(0, w.metrics().barrierHits);
    }
}
