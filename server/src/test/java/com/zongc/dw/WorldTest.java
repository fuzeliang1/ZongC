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
     * 外部设备在授权被收回时来不及反应（CI 中 Matlab 车队发现的问题）：车前方已授权的格子出了状况
     * （路障、感知发现的障碍），服务端收回授权，但设备在收到新停车点之前已经越过了新的授权终点一格。
     * 这不是设备违规，记入 revokeOverruns；收回之后过了宽限时间仍越界才算违规。
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
                if (a.route == null || a.route.last() < a.progress + 4) continue;
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

        // 车前方第 2 格出了状况：授权收回到第 1 格
        int p = car.progress;
        int lostCell = car.route.cell(p + 2);
        assertTrue(w.traffic().revokeBefore(car, p + 2));
        assertEquals("授权应收回到出状况的格子之前", p + 1, car.authority);

        // 设备还没收到新停车点，已经开进了这一格（原先已授权的范围内）
        reportAt(w, link, car, lostCell);
        w.step();
        assertEquals("来不及反应不算违规", 0, w.metrics().authorityViolations);
        assertEquals(1, w.metrics().revokeOverruns);

        // 宽限时间过后仍越过授权终点：算违规
        run(w, TrafficController.REVOKE_GRACE_SEC + 0.5);
        reportAt(w, link, car, car.route.cell(car.progress));     // 设备一直停在原地（保持在线）
        int beyond = Math.min(car.route.last(), car.authority + 2);
        if (beyond > car.authority) {
            reportAt(w, link, car, car.route.cell(beyond));
            w.step();
            assertEquals("宽限之后越界应算违规", 1, w.metrics().authorityViolations);
        }
    }

    /**
     * 手机模拟车的驾驶方式：设备不做转向控制，只沿 path 的路点折线按弧长前进（人走多远车就开多远），
     * 并且不越过 permit 的 stopS。这样也能完整地接客、送客，零越权、零碰撞。
     */
    @Test
    public void arcLengthDeviceCompletesTrips() throws Exception {
        Config cfg = TestSupport.config();
        World w = TestSupport.world(cfg);
        run(w, 2);
        FakeLink link = new FakeLink();
        w.onDeviceMessage(link, JsonParser.parseString("{\"type\":\"register\",\"kind\":\"phone\",\"agentId\":\"car-6\"}").getAsJsonObject());
        Agent car = w.agent("car-6");
        double walk = 1.2;                     // 人走路的速度（m/s），车速不超过 vMax
        double[] xs = null, ys = null, cum = null;
        int routeId = -1;
        double s = 0, stopS = 0;
        int seen = 0;
        for (int i = 0; i < 6 * 60 * 20; i++) {
            for (; seen < link.received.size(); seen++) {
                JsonObject m = link.received.get(seen);
                String type = m.get("type").getAsString();
                if (type.equals("path")) {
                    var wps = m.getAsJsonArray("waypoints");
                    xs = new double[wps.size()];
                    ys = new double[wps.size()];
                    cum = new double[wps.size()];
                    for (int k = 0; k < wps.size(); k++) {
                        xs[k] = wps.get(k).getAsJsonArray().get(0).getAsDouble();
                        ys[k] = wps.get(k).getAsJsonArray().get(1).getAsDouble();
                        if (k > 0) cum[k] = cum[k - 1] + Math.hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1]);
                    }
                    routeId = m.get("routeId").getAsInt();
                    s = 0;
                    stopS = 0;                 // 收到授权之前不能走
                } else if (type.equals("permit") && m.get("routeId").getAsInt() == routeId) {
                    stopS = m.get("stopS").getAsDouble();
                }
            }
            double v = 0;
            if (xs != null) {
                double next = Math.min(Math.min(stopS, cum[cum.length - 1]), s + walk * w.dt());
                v = Math.max(0, next - s) / w.dt();
                s = Math.max(s, next);
                int k = 1;
                while (k < cum.length - 1 && cum[k] < s) k++;
                double seg = Math.max(1e-9, cum[k] - cum[k - 1]);
                double u = Math.min(1, Math.max(0, (s - cum[k - 1]) / seg));
                double x = xs[k - 1] + (xs[k] - xs[k - 1]) * u, y = ys[k - 1] + (ys[k] - ys[k - 1]) * u;
                double th = cum.length > 1 ? Math.atan2(ys[k] - ys[k - 1], xs[k] - xs[k - 1]) : car.theta;
                w.onDeviceMessage(link, JsonParser.parseString("{\"type\":\"state\",\"x\":" + x + ",\"y\":" + y
                        + ",\"theta\":" + th + ",\"v\":" + v + "}").getAsJsonObject());
            }
            w.step();
            w.afterTick();
        }
        Metrics m = w.metrics();
        assertEquals(DriverKind.PHONE, car.driver);
        assertEquals("越权行驶", 0, m.authorityViolations);
        assertEquals("车车碰撞", 0, m.collisions);
        assertEquals("车墙碰撞", 0, m.obstacleHits);
        assertTrue("手机车应送达订单，实际 " + car.delivered, car.delivered >= 1);
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

        // 手机模拟车：和 Matlab 车一样绑定一辆数字车（驱动源记为手机），收到路线和授权，不新增智能体
        int before = w.agents().size();
        FakeLink phone = new FakeLink();
        w.onDeviceMessage(phone, JsonParser.parseString("{\"type\":\"register\",\"kind\":\"phone\",\"agentId\":\"car-6\"}").getAsJsonObject());
        Agent phoneCar = w.agent("car-6");
        assertEquals(DriverKind.PHONE, phoneCar.driver);
        assertEquals("car-6", phone.last("welcome").get("agentId").getAsString());
        assertNotNull(phone.last("path"));
        assertNotNull(phone.last("permit"));
        assertEquals(before, w.agents().size());
        phone.open = false;
        w.onDeviceClosed(phone);
        assertEquals(DriverKind.INTERNAL, phoneCar.driver);
    }
}
