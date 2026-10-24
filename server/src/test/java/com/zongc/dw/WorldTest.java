package com.zongc.dw;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zongc.dw.config.Config;
import com.zongc.dw.traffic.TrafficController;
import com.zongc.dw.world.Agent;
import com.zongc.dw.world.DeviceLink;
import com.zongc.dw.world.DriverKind;
import com.zongc.dw.world.Metrics;
import com.zongc.dw.world.World;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 整体仿真测试：长时间运行零碰撞、无死锁；路障绕行；设备接入与接管。 */
public class WorldTest {

    private static void run(World w, double seconds) {
        long steps = Math.round(seconds / w.dt());
        for (long i = 0; i < steps; i++) w.step();
    }

    @Test
    public void denseTrafficRunsWithoutCollisionsOrDeadlock() throws Exception {
        for (String strategy : new String[]{"GREEDY", "HUNGARIAN"}) {
            Config cfg = TestSupport.config();
            cfg.taxis = 16;
            cfg.tasks.ratePerMin = 16;
            cfg.dispatch.strategy = strategy;
            World w = TestSupport.world(cfg);
            run(w, 15 * 60);
            Metrics m = w.metrics();
            assertEquals(strategy + " 车车碰撞", 0, m.collisions);
            assertEquals(strategy + " 车墙碰撞", 0, m.obstacleHits);
            assertTrue(strategy + " 车距应为正", m.minCarGap > 0);
            assertTrue(strategy + " 离墙距离应为正", m.minWallGap > 0.15);
            assertTrue(strategy + " 不应出现长时间卡死", m.maxBlocked <= cfg.traffic.stuckRerouteSec + 1);
            assertTrue(strategy + " 应送达大量订单，实际 " + m.tasksDelivered, m.tasksDelivered > 150);
        }
    }

    @Test
    public void carsNeverEnterRoadblocks() throws Exception {
        Config cfg = TestSupport.config();
        World w = TestSupport.world(cfg);
        run(w, 20);
        // 在一条繁忙的南北向道路上设路障
        int[] blocked = {w.map().index(22, 10), w.map().index(23, 20)};
        for (int c : blocked) w.setRoadblock(c, true);
        double start = w.time();
        for (int i = 0; i < 20 * 60 * 5; i++) {
            w.step();
            if (w.time() - start < 5) continue;   // 给已经获得授权的车 5 秒驶离
            for (Agent a : w.agents()) {
                int cell = w.map().cellAt(a.x, a.y);
                for (int c : blocked) assertTrue(a.id + " 驶入了路障格", cell != c);
            }
        }
        assertEquals(0, w.metrics().collisions);
    }

    /** 行人（手机）突然走进出租车已获授权的格子：车必须在行人前停下，行人离开后继续行驶。 */
    @Test
    public void taxisStopForPedestriansInsideTheirAuthority() throws Exception {
        Config cfg = TestSupport.config();
        World w = TestSupport.world(cfg);
        run(w, 3);
        int trials = 0;
        for (int attempt = 0; attempt < 40 && trials < 5; attempt++) {
            Agent car = null;
            for (Agent a : w.agents()) {
                if (a.taxi && a.route != null && a.v > 0.5 && a.authority >= a.progress + 2) {
                    car = a;
                    break;
                }
            }
            if (car == null) {
                run(w, 0.5);
                continue;
            }
            trials++;
            int cell = car.route.cell(car.authority);         // 已经授权给这辆车的最远一格
            double px = w.map().centerX(cell), py = w.map().centerY(cell);
            FakeLink phone = new FakeLink();
            w.onDeviceMessage(phone, JsonParser.parseString("{\"type\":\"register\",\"kind\":\"phone\",\"x\":"
                    + px + ",\"y\":" + py + "}").getAsJsonObject());
            Agent ped = null;
            for (Agent a : w.agents()) if (!a.taxi) ped = a;
            assertNotNull(ped);
            int contacts = w.metrics().freeContacts;
            double minDist = Double.POSITIVE_INFINITY;
            boolean stopped = false;
            for (int i = 0; i < 80; i++) {                    // 4 s
                w.step();
                minDist = Math.min(minDist, Math.hypot(car.x - ped.x, car.y - ped.y));
                if (car.v < 0.01) stopped = true;
            }
            assertEquals(car.id + " 撞到了行人", contacts, w.metrics().freeContacts);
            assertTrue(car.id + " 没有停车", stopped);
            assertTrue("最小中心距 " + minDist, minDist > cfg.vehicle.bodyRadius + cfg.vehicle.phoneRadius);
            w.onDeviceClosed(phone);
            double s0 = car.s;
            run(w, 3);
            assertTrue(car.id + " 行人离开后没有继续行驶", car.s > s0 + 0.5 || car.route == null || car.v > 0.3);
        }
        assertTrue("应至少完成 3 次试验", trials >= 3);
        assertEquals(0, w.metrics().collisions);
        boolean logged = false;
        for (var e : w.events().recent(200)) if (e.msg().contains("紧急停车")) logged = true;
        assertTrue("事件日志里应有紧急停车记录", logged);
    }

    /** 测试用的假设备连接：记录服务端发来的消息。 */
    static final class FakeLink implements DeviceLink {
        final List<JsonObject> received = new ArrayList<>();
        boolean open = true;

        @Override public String id() { return "fake"; }
        @Override public String remote() { return "test:1"; }
        @Override public void send(String json) { received.add(JsonParser.parseString(json).getAsJsonObject()); }
        @Override public void close(String reason) { open = false; }
        @Override public boolean isOpen() { return open; }

        JsonObject last(String type) {
            for (int i = received.size() - 1; i >= 0; i--) {
                if (received.get(i).get("type").getAsString().equals(type)) return received.get(i);
            }
            return null;
        }
    }

    /**
     * 外部设备在授权被收回时来不及反应（CI 中 Matlab 车队发现的问题）：行人走进车前方已授权的格子，
     * 服务端收回授权，但设备在收到新停车点之前已经越过了新的授权终点一格。这不是设备违规，记入 revokeOverruns；
     * 收回之后过了宽限时间仍越界才算违规。
     */
    @Test
    public void lateReactionToRevocationIsNotAViolation() throws Exception {
        Config cfg = TestSupport.config();
        World w = TestSupport.world(cfg);
        run(w, 5);
        Agent car = null;
        FakeLink link = new FakeLink();
        for (int attempt = 0; attempt < 40 && car == null; attempt++) {
            for (Agent a : w.agents()) {
                if (!a.taxi || a.route == null || a.route.last() < a.progress + 4) continue;
                boolean straight = true;                     // 前方几格都不是路口，收回时不会退到路口前
                for (int i = a.progress; i <= a.progress + 3; i++) straight &= !w.map().isCross(a.route.cell(i));
                if (straight && a.authority >= a.progress + 2) {
                    car = a;
                    break;
                }
            }
            if (car == null) run(w, 0.5);
        }
        assertNotNull("应能找到前方有直道授权的车", car);
        w.onDeviceMessage(link, JsonParser.parseString("{\"type\":\"register\",\"kind\":\"matlab\",\"agentId\":\""
                + car.id + "\"}").getAsJsonObject());
        reportAt(w, link, car, car.route.cell(car.progress));
        for (int i = 0; i < 5 && car.authority < car.progress + 2; i++) w.step();
        assertTrue("接管后授权应延伸到前方两格（且不是路口）", car.authority >= car.progress + 2
                && !w.map().isCross(car.route.cell(car.progress + 1)) && !w.map().isCross(car.route.cell(car.progress + 2)));

        // 行人走进车前方第 2 格：授权收回到第 1 格
        int p = car.progress;
        int pedCell = car.route.cell(p + 2);
        FakeLink phone = new FakeLink();
        w.onDeviceMessage(phone, JsonParser.parseString("{\"type\":\"register\",\"kind\":\"phone\",\"x\":"
                + w.map().centerX(pedCell) + ",\"y\":" + w.map().centerY(pedCell) + "}").getAsJsonObject());
        w.step();
        assertEquals("授权应收回到行人之前", p + 1, car.authority);

        // 设备还没收到新停车点，已经开进了行人所在的格子（原先已授权的范围内）
        reportAt(w, link, car, pedCell);
        w.step();
        assertEquals("来不及反应不算违规", 0, w.metrics().authorityViolations);
        assertEquals(1, w.metrics().revokeOverruns);

        // 宽限时间过后仍越过授权终点：算违规
        w.onDeviceClosed(phone);
        run(w, TrafficController.REVOKE_GRACE_SEC + 0.5);
        reportAt(w, link, car, car.route.cell(car.progress));     // 设备一直停在原地（保持在线）
        int beyond = Math.min(car.route.last(), car.authority + 2);
        if (beyond > car.authority) {
            reportAt(w, link, car, car.route.cell(beyond));
            w.step();
            assertEquals("宽限之后越界应算违规", 1, w.metrics().authorityViolations);
        }
    }

    /** 让外部设备上报自己停在某一格的中心。 */
    private static void reportAt(World w, FakeLink link, Agent car, int cell) {
        w.onDeviceMessage(link, JsonParser.parseString("{\"type\":\"state\",\"x\":" + w.map().centerX(cell) + ",\"y\":"
                + w.map().centerY(cell) + ",\"theta\":" + car.theta + ",\"v\":0}").getAsJsonObject());
    }

    @Test
    public void deviceTakesOverAndFallsBack() throws Exception {
        Config cfg = TestSupport.config();
        World w = TestSupport.world(cfg);
        run(w, 5);
        FakeLink link = new FakeLink();
        w.onDeviceMessage(link, JsonParser.parseString("{\"type\":\"register\",\"kind\":\"matlab\",\"agentId\":\"car-3\"}").getAsJsonObject());
        Agent car = w.agent("car-3");
        assertEquals(DriverKind.MATLAB, car.driver);
        JsonObject welcome = link.last("welcome");
        assertNotNull(welcome);
        assertEquals("car-3", welcome.get("agentId").getAsString());
        JsonObject path = link.last("path");
        JsonObject permit = link.last("permit");
        assertNotNull(path);
        assertNotNull(permit);
        assertEquals(path.get("routeId").getAsInt(), permit.get("routeId").getAsInt());
        // 接管行驶中的车时，路线应从车辆当前所在格开始
        int[] firstCell = {path.getAsJsonArray("cells").get(0).getAsJsonArray().get(0).getAsInt(),
                path.getAsJsonArray("cells").get(0).getAsJsonArray().get(1).getAsInt()};
        assertEquals(w.map().cellAt(car.x, car.y), w.map().index(firstCell[0], firstCell[1]));

        // 设备上报状态
        w.onDeviceMessage(link, JsonParser.parseString("{\"type\":\"state\",\"x\":" + car.x + ",\"y\":" + car.y
                + ",\"theta\":" + car.theta + ",\"v\":0}").getAsJsonObject());
        assertEquals(1, car.stateCount);

        // 断开后由数字世界接管，仿真继续
        link.open = false;
        w.onDeviceClosed(link);
        assertEquals(DriverKind.INTERNAL, car.driver);
        run(w, 30);
        assertEquals(0, w.metrics().collisions);

        // 手机作为自由智能体接入
        FakeLink phone = new FakeLink();
        w.onDeviceMessage(phone, JsonParser.parseString("{\"type\":\"register\",\"kind\":\"phone\",\"x\":10,\"y\":10}").getAsJsonObject());
        assertNotNull(w.agent("phone-1"));
        w.onDeviceClosed(phone);
        assertEquals(null, w.agent("phone-1"));
    }
}
