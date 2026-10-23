package com.zongc.phone.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HeadingEstimatorTest {
    private static final double DEG = Math.PI / 180;

    /** 像 PdrEngine 一样：加速度 → 步伐检测器（判断静止）+ 航向估计器。 */
    static final class Rig implements SyntheticWalker.Sink {
        final PdrConfig cfg;
        final HeadingEstimator h;
        final StepDetector d;
        final java.util.List<StepDetector.Step> sink = new java.util.ArrayList<StepDetector.Step>();
        boolean feedMag = true;
        boolean feedGyro = true;

        Rig(PdrConfig cfg) {
            this.cfg = cfg;
            h = new HeadingEstimator(cfg);
            d = new StepDetector(cfg);
        }

        @Override
        public void acc(long t, double x, double y, double z) {
            h.onAccel(t, x, y, z);
            d.update(t, x, y, z, sink);
        }

        @Override
        public void gyro(long t, double x, double y, double z) {
            if (feedGyro) h.onGyro(t, x, y, z, d.isStill(t, 0.8));
        }

        @Override
        public void mag(long t, double x, double y, double z) {
            if (feedMag) h.onMag(t, x, y, z);
        }
    }

    private static Rig gyroOnly() {
        Rig r = new Rig(new PdrConfig());
        r.feedMag = false;
        return r;
    }

    @Test
    public void integratesTurnsWhenFlat() {
        SyntheticWalker w = new SyntheticWalker(11);
        w.pitchDeg = 0;
        Rig r = gyroOnly();
        w.stand(1, 0, r);
        double y0 = r.h.yaw();
        w.turn(90 * DEG, 2, r);
        w.stand(1, 0, r);
        assertEquals(90, Geo.diff(r.h.yaw(), y0) / DEG, 1.5);
    }

    @Test
    public void integratesTurnsWhenTiltedOrRolled() {
        double[][] poses = {{40, 15}, {75, 0}, {20, -30}};
        for (double[] p : poses) {
            SyntheticWalker w = new SyntheticWalker(12);
            w.pitchDeg = p[0];
            w.rollDeg = p[1];
            Rig r = gyroOnly();
            w.stand(1, 0, r);
            double y0 = r.h.yaw();
            w.turn(-150 * DEG, 3, r);
            w.stand(1, 0, r);
            assertEquals("姿态 " + p[0] + "/" + p[1], -150, Geo.diff(r.h.yaw(), y0) / DEG, 2.0);
        }
    }

    @Test
    public void tracksTurnsWhileWalking() {
        SyntheticWalker w = new SyntheticWalker(13);
        Rig r = gyroOnly();
        w.stand(1, 0, r);
        double y0 = r.h.yaw();
        w.walk(10, 0.2, r);                               // 边走边转 2 rad
        assertEquals(2.0 / DEG, Geo.diff(r.h.yaw(), y0) / DEG, 3.0);
    }

    @Test
    public void magneticHeadingMatchesTrueHeading() {
        double[] yaws = {0, 45, 135, -100, 179};
        double[] pitches = {0, 35, 70, 88};
        for (double yawDeg : yaws) {
            for (double pitch : pitches) {
                SyntheticWalker w = new SyntheticWalker(14);
                w.yaw = yawDeg * DEG;
                w.pitchDeg = pitch;
                Rig r = new Rig(new PdrConfig());
                w.stand(0.5, 0, r);
                double err = Geo.diff(r.h.magYaw(), w.yaw) / DEG;
                assertEquals("航向 " + yawDeg + "° 俯仰 " + pitch + "°", 0, err, 2.0);
            }
        }
    }

    @Test
    public void initialHeadingComesFromMagnetometer() {
        SyntheticWalker w = new SyntheticWalker(15);
        w.yaw = 30 * DEG;
        Rig r = new Rig(new PdrConfig());
        w.stand(1, 0, r);
        assertEquals(30, r.h.yaw() / DEG, 2.0);
    }

    @Test
    public void learnsGyroBiasWhileStill() {
        SyntheticWalker w = new SyntheticWalker(16);
        w.pitchDeg = 0;
        w.gyroBiasZ = 0.02;                                 // 1.15°/s，很差的陀螺
        Rig r = gyroOnly();
        w.stand(30, 0, r);
        assertEquals(0.02, r.h.biasUp(), 0.003);
        double y0 = r.h.yaw();
        w.stand(60, 0, r);
        assertEquals(0, Geo.diff(r.h.yaw(), y0) / DEG, 2.0);

        // 关闭零偏估计：同样 60 s 会漂移约 69°
        SyntheticWalker w2 = new SyntheticWalker(16);
        w2.pitchDeg = 0;
        w2.gyroBiasZ = 0.02;
        PdrConfig cfg = new PdrConfig();
        cfg.gyroBiasEstimation = false;
        Rig r2 = new Rig(cfg);
        r2.feedMag = false;
        w2.stand(30, 0, r2);
        double z0 = r2.h.yaw();
        w2.stand(60, 0, r2);
        assertTrue(Math.abs(Geo.diff(r2.h.yaw(), z0)) / DEG > 50);
    }

    @Test
    public void magnetometerCorrectionRemovesDriftOutdoors() {
        double[] errors = new double[2];
        double worst = 0;
        for (int k = 0; k < 2; k++) {
            SyntheticWalker w = new SyntheticWalker(17);
            w.gyroBiasZ = 0.01;                             // 0.57°/s 的剩余零偏
            Rig r = new Rig(new PdrConfig());
            r.h.setMagCorrection(k == 0);
            w.stand(0.5, 0, r);
            for (int sec = 0; sec < 150; sec++) {           // 一直在走（静止零偏估计不起作用），偶尔转弯
                w.walk(1, (sec / 20) % 3 == 1 ? 0.08 : 0, r);
                if (k == 0) worst = Math.max(worst, Math.abs(Geo.diff(r.h.yaw(), w.yaw)) / DEG);
            }
            errors[k] = Math.abs(Geo.diff(r.h.yaw(), w.yaw)) / DEG;
            // 零偏加在设备 z 轴上，手机俯仰 35°，所以竖直分量是 0.01·cos35°
            if (k == 0) assertEquals("学到的零偏", 0.01 * Math.cos(35 * DEG), r.h.biasUp(), 0.001);
        }
        assertTrue("有磁修正时最终误差 " + errors[0] + "°", errors[0] < 2);
        assertTrue("有磁修正时最大误差 " + worst + "°", worst < 8);
        assertTrue("无磁修正时误差 " + errors[1] + "°", errors[1] > 60);
    }

    @Test
    public void historyLookupInterpolates() {
        SyntheticWalker w = new SyntheticWalker(18);
        w.pitchDeg = 0;
        w.gyroNoise = 0;
        Rig r = gyroOnly();
        w.stand(0.5, 0, r);
        long tStart = w.t;
        double y0 = r.h.yaw();
        w.turn(1.0, 2, r);                                  // 0.5 rad/s
        long tMid = tStart + 1_000_000_000L;
        assertEquals(0.5, Geo.diff(r.h.yawAt(tMid), y0), 0.02);
        assertEquals(1.0, Geo.diff(r.h.yawAt(w.t + 5), y0), 0.02);
        assertEquals(0.0, Geo.diff(r.h.yawAt(0), y0), 0.02);  // 早于历史：取最早
    }

    @Test
    public void phoneWithoutGyroFollowsMagnetometer() {
        SyntheticWalker w = new SyntheticWalker(19);
        Rig r = new Rig(new PdrConfig());
        r.feedGyro = false;
        w.stand(1, 0, r);
        w.turn(90 * DEG, 2, r);
        w.stand(2, 0, r);
        assertEquals(0, Geo.diff(r.h.yaw(), w.yaw) / DEG, 3.0);
    }
}
