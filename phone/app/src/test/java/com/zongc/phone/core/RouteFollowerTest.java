package com.zongc.phone.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RouteFollowerTest {
    private static final long DT = 100_000_000L;   // 10 Hz，与上报频率相同

    private long now = 1_000_000_000L;

    private static void send(RouteFollower f, String json) {
        f.onMessage(Json.parseObject(json));
    }

    private static RouteFollower welcomed(double x, double y, double theta) {
        RouteFollower f = new RouteFollower();
        send(f, "{\"type\":\"welcome\",\"agentId\":\"car-6\",\"pose\":{\"x\":" + x + ",\"y\":" + y + ",\"theta\":" + theta
                + ",\"v\":1.2},\"params\":{\"vMax\":1.5,\"aAcc\":1.0,\"aDec\":1.5,\"vTurn\":0.6}}");
        return f;
    }

    /** 沿 +x 的直线路线：(x0, 0.5) → (x1, 0.5)，每 0.25 m 一个路点。 */
    private static String straightPath(int routeId, double x0, double x1) {
        StringBuilder sb = new StringBuilder("{\"type\":\"path\",\"agentId\":\"car-6\",\"routeId\":" + routeId
                + ",\"purpose\":\"PICKUP\",\"status\":\"TO_PICKUP\",\"taskId\":\"T003\",\"waypoints\":[");
        int n = (int) Math.round((x1 - x0) / 0.25);
        for (int i = 0; i <= n; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(x0 + 0.25 * i).append(",0.5]");
        }
        return sb.append("]}").toString();
    }

    private static String permit(int routeId, double stopS, boolean fin) {
        return "{\"type\":\"permit\",\"agentId\":\"car-6\",\"routeId\":" + routeId + ",\"stopS\":" + stopS
                + ",\"final\":" + fin + "}";
    }

    private double[] run(RouteFollower f, double wanted, double seconds) {
        double[] p = null;
        for (int i = 0; i < Math.round(seconds * 10); i++) {
            now += DT;
            p = f.step(now, wanted);
        }
        return p;
    }

    @Test
    public void reportsNothingBeforeWelcomeThenHoldsTheWelcomePose() {
        RouteFollower f = new RouteFollower();
        assertNull(f.step(now, 1.2));
        f = welcomed(10.5, 3.5, Math.PI / 2);
        double[] p = run(f, 1.2, 1);
        assertNotNull(p);
        assertEquals(10.5, p[0], 1e-9);
        assertEquals(3.5, p[1], 1e-9);
        assertEquals(Math.PI / 2, p[2], 1e-9);
        assertEquals("没有路线时走路车也不动", 0, p[3], 1e-9);
        assertEquals("car-6", f.status().agentId);
    }

    @Test
    public void newRouteWaitsForItsPermitAndNeverPassesStopS() {
        RouteFollower f = welcomed(0.5, 0.5, 0);
        send(f, straightPath(1, 0.5, 6.5));
        run(f, 1.2, 2);
        assertEquals("收到 permit 之前不能走", 0, f.status().s, 1e-9);
        assertTrue(f.status().waitingPermit());

        send(f, permit(1, 2.0, false));
        for (int i = 0; i < 60; i++) {
            now += DT;
            f.step(now, 1.2);
            assertTrue("s 不能超过 stopS", f.status().s <= 2.0 + 1e-9);
        }
        RouteFollower.Status st = f.status();
        assertEquals(2.0, st.s, 1e-6);
        assertEquals(2.5, st.x, 1e-6);
        assertEquals(0, st.v, 1e-9);
        assertTrue(st.waitingPermit());
        assertEquals("TO_PICKUP", st.carStatus);
        assertEquals("T003", st.taskId);
        assertEquals(4.0, st.remaining(), 1e-6);
        assertEquals("车前方第一个路点", 9, st.next);
        assertEquals(9, st.stopNext);
        assertEquals(25 * 2, st.waypoints.length);

        // 授权收回到车后面：车停住，不会倒退
        send(f, permit(1, 1.0, false));
        run(f, 1.2, 1);
        assertEquals(2.0, f.status().s, 1e-6);

        // 旧路线的授权被忽略
        send(f, permit(0, 5.0, false));
        run(f, 1.2, 1);
        assertEquals(2.0, f.status().s, 1e-6);
    }

    @Test
    public void walkingSpeedIsTheThrottle() {
        RouteFollower f = welcomed(0.5, 0.5, 0);
        send(f, straightPath(1, 0.5, 40.5));
        send(f, permit(1, 40, true));
        double[] p = run(f, 1.0, 3);
        assertEquals("车速跟随走路速度", 1.0, p[3], 1e-6);
        assertEquals(0, p[2], 1e-9);

        double before = f.status().s;
        p = run(f, 3.0, 4);
        assertEquals("不超过最高车速", 1.5, p[3], 1e-6);
        assertTrue(f.status().s - before < 1.5 * 4 + 1e-6);

        // 停下：按 aDec 减速，不会瞬间停住
        double v = p[3];
        for (int i = 0; i < 20; i++) {
            now += DT;
            double nv = f.step(now, 0)[3];
            assertTrue("减速度不超过 aDec", v - nv <= 1.5 * 0.1 + 1e-9);
            v = nv;
        }
        assertEquals(0, v, 1e-9);
    }

    @Test
    public void slowsDownForTurnsAndFollowsTheArc() {
        // 先向东 3 m，再沿半径 0.5 的四分之一圆弧左转，再向北 3 m
        StringBuilder sb = new StringBuilder("{\"type\":\"path\",\"routeId\":4,\"waypoints\":[");
        for (int i = 0; i <= 12; i++) sb.append('[').append(0.25 * i).append(",0],");
        for (int k = 1; k <= 4; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 8;
            sb.append('[').append(3 + 0.5 * Math.cos(a)).append(',').append(0.5 + 0.5 * Math.sin(a)).append("],");
        }
        for (int i = 1; i <= 12; i++) sb.append("[3.5,").append(0.5 + 0.25 * i).append(i < 12 ? "]," : "]");
        sb.append("]}");
        RouteFollower f = welcomed(0, 0, 0);
        send(f, sb.toString());
        send(f, permit(4, 100, true));
        double maxInArc = 0;
        for (int i = 0; i < 200; i++) {
            now += DT;
            double[] p = f.step(now, 3.0);
            if (p[0] > 3.05 && p[1] < 0.45) maxInArc = Math.max(maxInArc, p[3]);
            assertTrue("车始终在路线上", (Math.abs(p[1]) < 1e-6 && p[0] <= 3 + 1e-6)
                    || (Math.abs(p[0] - 3.5) < 1e-6 && p[1] >= 0.5 - 1e-6)
                    || Math.abs(Math.hypot(p[0] - 3, p[1] - 0.5) - 0.5) < 0.04);
        }
        assertTrue("转弯段限速 vTurn，实际 " + maxInArc, maxInArc > 0 && maxInArc <= 0.6 + 1e-6);
        RouteFollower.Status st = f.status();
        assertTrue(st.arrived());
        assertFalse(st.waitingPermit());
        assertEquals(3.5, st.x, 1e-6);
        assertEquals(3.5, st.y, 1e-6);
        assertEquals("终点朝北", Math.PI / 2, st.theta, 1e-6);
        assertEquals(0, st.v, 1e-9);
    }

    @Test
    public void newRouteStartsWhereTheCarIs() {
        RouteFollower f = welcomed(0.5, 0.5, 0);
        send(f, straightPath(1, 0.5, 6.5));
        send(f, permit(1, 6, false));
        run(f, 1.2, 3);
        double x = f.status().x;
        assertTrue(x > 2 && x < 5);

        // 改道：新路线从当前所在格的入口边出发，车投影到新路线上，位置不跳变；收到新 permit 前停车
        send(f, straightPath(2, 2.0, 9.0));
        RouteFollower.Status st = f.status();
        assertEquals(x, st.x, 1e-6);
        assertEquals(x - 2.0, st.s, 1e-6);
        assertEquals(2, st.routeId);
        assertFalse(st.permitted);
        run(f, 1.2, 1);
        assertEquals(x, f.status().x, 1e-6);
        send(f, permit(2, 7, true));
        run(f, 1.2, 1);
        assertTrue(f.status().x > x);

        // 车稍微在新路线起点后面（例如掉头路线从格子中心出发）：沿第一段反向延长接上
        RouteFollower g = welcomed(1.2, 0.5, Math.PI);
        send(g, straightPath(3, 1.5, 4.0));
        assertEquals(-0.3, g.status().s, 1e-6);
        assertEquals(1.2, g.status().x, 1e-6);
        assertEquals("航向取路线方向", 0, g.status().theta, 1e-9);

        // 断线后清空，重连收到 welcome 再开始上报
        f.reset();
        assertNull(f.step(now + DT, 1.2));
        assertFalse(f.status().hasRoute);
    }
}
