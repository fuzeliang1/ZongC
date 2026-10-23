package com.zongc.phone.core;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PdrEngineTest {
    private static final double DEG = Math.PI / 180;

    private static PdrConfig cfg() {
        PdrConfig c = new PdrConfig();
        c.smoothingTau = 0;          // 测试位置时不要平滑延迟
        return c;
    }

    @Test
    public void walkingASquareReturnsHome() {
        SyntheticWalker w = new SyntheticWalker(21);
        PdrEngine e = new PdrEngine(cfg());
        SyntheticWalker.Sink s = SyntheticWalker.into(e);
        w.stand(2, 0, s);
        PdrState start = e.state(w.t);
        for (int side = 0; side < 4; side++) {
            w.walk(8, 0, s);
            w.stand(0.5, 0, s);
            w.turn(90 * DEG, 1.5, s);
            w.stand(0.5, 0, s);
        }
        w.stand(2, 0, s);
        PdrState end = e.state(w.t);
        double side = w.stepLength * w.stepHz * 8;
        double closure = Math.hypot(end.px - start.px, end.py - start.py);
        assertTrue("闭合误差 " + closure + " m（周长 " + 4 * side + " m）", closure < 0.05 * 4 * side);
        assertEquals(w.trueSteps, end.steps, 2);
        assertEquals("距离 " + end.distance, 4 * side, end.distance, 0.15 * 4 * side);
        assertFalse(end.walking);
        assertEquals(0, end.v, 0);
    }

    @Test
    public void setPositionAndAlignHeadingDefineTheMapFrame() {
        SyntheticWalker w = new SyntheticWalker(22);
        w.yaw = 70 * DEG;                                   // 人实际朝向随意
        PdrEngine e = new PdrEngine(cfg());
        SyntheticWalker.Sink s = SyntheticWalker.into(e);
        w.stand(2, 0, s);
        e.setPosition(20, 8.5);
        e.alignHeading(0);                                  // "我现在面朝地图 +x 方向"
        PdrState a = e.state(w.t);
        assertEquals(20, a.x, 1e-9);
        assertEquals(8.5, a.y, 1e-9);
        assertEquals(0, a.theta, 1e-9);
        w.walkSteps(10, s);
        w.stand(2, 0, s);
        PdrState b = e.state(w.t);
        assertEquals(10, b.steps, 1);
        assertTrue("沿 +x 前进：" + b.x, b.x - 20 > 5);
        assertEquals(8.5, b.y, 0.4);
        assertEquals(0, b.theta / DEG, 3);
    }

    @Test
    public void faceTowardPointsHeadingAtTarget() {
        SyntheticWalker w = new SyntheticWalker(23);
        PdrEngine e = new PdrEngine(cfg());
        SyntheticWalker.Sink s = SyntheticWalker.into(e);
        w.stand(1, 0, s);
        e.setPosition(10, 10);
        e.faceToward(10, 20);                               // 正对地图上方的一点
        assertEquals(90, e.state(w.t).theta / DEG, 1e-6);
        w.turn(-90 * DEG, 2, s);                            // 向右转 90°
        assertEquals(0, e.state(w.t).theta / DEG, 2);
    }

    @Test
    public void mapScaleMultipliesDisplacementWithoutJumping() {
        SyntheticWalker w = new SyntheticWalker(24);
        PdrEngine e = new PdrEngine(cfg());
        SyntheticWalker.Sink s = SyntheticWalker.into(e);
        w.stand(2, 0, s);
        e.setPosition(10, 10);
        w.walkSteps(10, s);
        w.stand(2, 0, s);
        PdrState a = e.state(w.t);
        double d1 = Math.hypot(a.x - 10, a.y - 10);

        PdrConfig c = e.config();
        c.mapScale = 3;
        e.applyConfig(c);
        PdrState b = e.state(w.t);
        assertEquals("改比例尺不应跳变", a.x, b.x, 1e-9);
        assertEquals(a.y, b.y, 1e-9);

        w.walkSteps(10, s);
        w.stand(2, 0, s);
        PdrState d = e.state(w.t);
        double d2 = Math.hypot(d.x - b.x, d.y - b.y);
        assertEquals(3 * d1, d2, 0.15 * 3 * d1);
        assertEquals(3, d.scale, 0);
    }

    @Test
    public void simulatedWalkingMovesAlongPhoneHeading() {
        SyntheticWalker w = new SyntheticWalker(25);
        PdrEngine e = new PdrEngine(cfg());
        SyntheticWalker.Sink s = SyntheticWalker.into(e);
        w.pitchDeg = 0;                                     // 手机平放在桌上
        w.stand(1, 0, s);
        e.setPosition(5, 5);
        e.alignHeading(90 * DEG);                           // 机头朝地图 +y
        e.setSimWalk(true, w.t);
        long t0 = w.t;
        PdrState mid = null;
        for (int i = 1; i <= 100; i++) {                    // 10 s，每 0.1 s 取一次状态
            w.stand(0.1, 0, s);
            PdrState st = e.state(w.t);
            if (i == 50) mid = st;
        }
        PdrState end = e.state(w.t);
        int expectedSteps = 19;                             // t0, t0 + 0.556 s, …, t0 + 10 s
        assertEquals(expectedSteps, end.steps);
        assertEquals(5, end.x, 0.3);
        assertEquals(5 + expectedSteps * 0.65, end.y, 0.3);
        assertTrue(mid.walking);
        assertEquals(0.65 * 1.8, mid.v, 0.05);
        assertTrue(end.simWalk);

        e.setSimWalk(false, w.t);
        w.stand(3, 0, s);
        PdrState after = e.state(w.t);
        assertEquals(end.steps, after.steps);
        assertFalse(after.walking);
        assertTrue(w.t > t0);
    }

    @Test
    public void stepCalibrationFixesTheStepLength() {
        SyntheticWalker w = new SyntheticWalker(26);
        w.stepLength = 0.78;                                // 这个人步子大
        PdrEngine e = new PdrEngine(cfg());
        SyntheticWalker.Sink s = SyntheticWalker.into(e);
        w.stand(2, 0, s);
        e.startCalibration();
        double x0 = w.x, y0 = w.y;
        w.walkSteps(16, s);
        w.stand(2, 0, s);
        double real = Math.hypot(w.x - x0, w.y - y0);
        assertTrue(e.state(w.t).calibrating);
        double k = e.finishCalibration(real);
        assertFalse(Double.isNaN(k));
        assertEquals(k, e.config().weinbergK, 0);

        PdrState before = e.state(w.t);
        double xs = w.x, ys = w.y;
        w.walkSteps(20, s);
        w.stand(2, 0, s);
        PdrState after = e.state(w.t);
        double realAgain = Math.hypot(w.x - xs, w.y - ys);
        assertEquals(realAgain, after.distance - before.distance, 0.05 * realAgain);
    }

    @Test
    public void calibrationWithTooFewStepsIsRejected() {
        PdrEngine e = new PdrEngine(cfg());
        e.startCalibration();
        assertTrue(Double.isNaN(e.finishCalibration(10)));
        assertEquals(new PdrConfig().weinbergK, e.config().weinbergK, 0);
    }

    @Test
    public void gnssKeepsLongWalksOnTrack() {
        double[] errors = new double[2];
        for (int k = 0; k < 2; k++) {
            SyntheticWalker w = new SyntheticWalker(27);
            w.yaw = 20 * DEG;
            PdrConfig c = cfg();
            c.outdoor = true;
            c.weinbergK = 0.45 * 1.3;                       // 步长模型偏大 30%：PDR 会越走越远
            PdrEngine e = new PdrEngine(c);
            SyntheticWalker.Sink s = SyntheticWalker.into(e);
            Random rnd = new Random(5);
            double lat0 = 31.2850, lon0 = 121.5020;
            w.stand(2, 0, s);
            for (int sec = 0; sec < 300; sec++) {           // 5 分钟，约 330 m
                w.walk(1, (sec / 60) % 2 == 1 ? 0.03 : 0, s);
                if (k == 0) {
                    double nx = w.x + 3 * rnd.nextGaussian(), ny = w.y + 3 * rnd.nextGaussian();
                    double lat = lat0 + Math.toDegrees(ny / Geo.EARTH_RADIUS);
                    double lon = lon0 + Math.toDegrees(nx / (Geo.EARTH_RADIUS * Math.cos(Math.toRadians(lat0))));
                    e.onLocation(w.t, lat, lon, 5);
                }
            }
            PdrState st = e.state(w.t);
            // P 坐标系与模拟器的世界坐标系原点相同（都从 (0,0) 出发，航向都以磁北为准）
            errors[k] = Math.hypot(st.px - w.x, st.py - w.y);
            if (k == 0) {
                assertTrue(st.gnssUsed);
                assertFalse(Double.isNaN(st.posStd));
            }
        }
        // 步长偏大 30% 会让整条轨迹以起点为中心放大 30%：不融合时误差 ≈ 0.3 × 直线距离
        assertTrue("有卫星融合时误差 " + errors[0] + " m", errors[0] < 8);
        assertTrue("无卫星融合时误差 " + errors[1] + " m", errors[1] > 25 && errors[1] > 4 * errors[0]);
    }

    @Test
    public void indoorModeIgnoresGnssAndBadFixesAreRejected() {
        PdrEngine e = new PdrEngine(cfg());
        e.onLocation(1, 31.28, 121.50, 4);
        e.onLocation(2, 31.29, 121.51, 4);
        PdrState st = e.state(3);
        assertEquals(0, st.px, 0);
        assertFalse(st.gnssUsed);
        assertEquals(31.29, st.gnssLat, 0);

        PdrConfig c = cfg();
        c.outdoor = true;
        PdrEngine o = new PdrEngine(c);
        o.onLocation(1, 31.28, 121.50, 80);                 // 精度太差
        assertFalse(o.state(2).gnssUsed);
    }

    @Test
    public void switchingToOutdoorModeKeepsMapPoseContinuous() {
        SyntheticWalker w = new SyntheticWalker(28);
        w.yaw = 10 * DEG;
        PdrEngine e = new PdrEngine(cfg());
        SyntheticWalker.Sink s = SyntheticWalker.into(e);
        w.stand(1, 0, s);
        w.walk(5, 0.3, s);                                  // 走一段、转一转，让陀螺航向与磁北有偏差
        w.stand(1, 0, s);
        e.setPosition(12, 7);
        e.alignHeading(1.0);
        PdrState a = e.state(w.t);
        PdrConfig c = e.config();
        c.outdoor = true;
        e.applyConfig(c);
        PdrState b = e.state(w.t);
        assertEquals(a.x, b.x, 1e-9);
        assertEquals(a.y, b.y, 1e-9);
        assertEquals(0, Geo.diff(a.theta, b.theta), 1e-9);
        assertEquals("内部航向对齐磁北", 0, Geo.diff(b.yawP, w.yaw) / DEG, 3);
        assertTrue(b.outdoor);
    }

    @Test
    public void restoreAndSmoothing() {
        PdrConfig c = new PdrConfig();
        c.smoothingTau = 0.5;
        PdrEngine e = new PdrEngine(c);
        e.restore(12, 7, 0.5);
        PdrState a = e.state(1_000_000_000L);
        assertEquals(12, a.x, 1e-9);
        assertEquals(7, a.y, 1e-9);
        assertEquals(0.5, Geo.diff(a.theta, a.yawP), 1e-9);

        // 模拟行走一步后，输出位置应平滑地追上真实位置
        e.setSimWalk(true, 1_000_000_000L);
        PdrState b = e.state(1_000_000_000L);          // 第一步立即发生
        assertTrue(Math.hypot(b.rawX - 12, b.rawY - 7) > 0.6);
        assertEquals(12, b.x, 1e-9);                    // dt = 0：输出还没动
        PdrState c1 = e.state(1_100_000_000L);
        double gap1 = Math.hypot(c1.rawX - c1.x, c1.rawY - c1.y);
        assertTrue(gap1 > 0.3 && gap1 < 0.65);
        e.setSimWalk(false, 1_100_000_000L);
        PdrState c2 = e.state(4_000_000_000L);
        assertEquals(c2.rawX, c2.x, 0.01);
        assertEquals(c2.rawY, c2.y, 0.01);
    }

    @Test
    public void configSurvivesSerialization() {
        PdrConfig c = new PdrConfig();
        c.weinbergK = 0.512;
        c.mapScale = 4;
        c.outdoor = true;
        c.declinationDeg = -5.25;
        PdrConfig back = PdrConfig.fromJson(c.toJson());
        assertEquals(0.512, back.weinbergK, 1e-9);
        assertEquals(4, back.mapScale, 0);
        assertTrue(back.outdoor);
        assertEquals(-5.25, back.declinationDeg, 1e-9);
        // 损坏或不合理的值：用默认值
        PdrConfig broken = PdrConfig.fromJson("{\"weinbergK\":-3,\"mapScale\":\"x\"");
        assertEquals(new PdrConfig().weinbergK, broken.weinbergK, 0);
        PdrConfig partial = PdrConfig.fromJson("{\"weinbergK\":-3,\"mapScale\":2}");
        assertEquals(new PdrConfig().weinbergK, partial.weinbergK, 0);
        assertEquals(2, partial.mapScale, 0);
    }
}
