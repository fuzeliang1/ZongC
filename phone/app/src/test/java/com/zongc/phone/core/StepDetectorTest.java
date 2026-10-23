package com.zongc.phone.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StepDetectorTest {

    /** 只把加速度送进步伐检测器。 */
    static final class Counter implements SyntheticWalker.Sink {
        final StepDetector detector;
        final List<StepDetector.Step> steps = new ArrayList<StepDetector.Step>();

        Counter(PdrConfig cfg) {
            detector = new StepDetector(cfg);
        }

        @Override
        public void acc(long t, double x, double y, double z) {
            detector.update(t, x, y, z, steps);
        }

        @Override
        public void gyro(long t, double x, double y, double z) {
        }

        @Override
        public void mag(long t, double x, double y, double z) {
        }
    }

    private static int countWalking(SyntheticWalker w, double seconds) {
        Counter c = new Counter(new PdrConfig());
        w.stand(2, 0, c);
        w.walk(seconds, 0, c);
        w.stand(2, 0, c);
        return c.steps.size();
    }

    @Test
    public void countsNormalWalking() {
        SyntheticWalker w = new SyntheticWalker(1);
        int n = countWalking(w, 20);
        assertEquals(36, w.trueSteps);
        assertTrue("检测到 " + n + " 步", Math.abs(n - w.trueSteps) <= 1);
    }

    @Test
    public void countsSlowAndFastWalking() {
        SyntheticWalker slow = new SyntheticWalker(2);
        slow.stepHz = 1.2;
        slow.bounce = 1.2;
        int n = countWalking(slow, 30);
        assertTrue("慢走：检测 " + n + " / 实际 " + slow.trueSteps, Math.abs(n - slow.trueSteps) <= 1);

        SyntheticWalker fast = new SyntheticWalker(3);
        fast.stepHz = 2.6;
        fast.bounce = 4.5;
        int m = countWalking(fast, 20);
        assertTrue("快走：检测 " + m + " / 实际 " + fast.trueSteps, Math.abs(m - fast.trueSteps) <= 1);
    }

    @Test
    public void independentOfHowThePhoneIsHeld() {
        double[][] poses = {{0, 0}, {35, 0}, {80, 0}, {30, 40}};   // 俯仰, 横滚
        for (double[] p : poses) {
            SyntheticWalker w = new SyntheticWalker(4);
            w.pitchDeg = p[0];
            w.rollDeg = p[1];
            int n = countWalking(w, 15);
            assertTrue("姿态 " + p[0] + "/" + p[1] + "：检测 " + n + " / 实际 " + w.trueSteps,
                    Math.abs(n - w.trueSteps) <= 1);
        }
    }

    @Test
    public void standingStillGivesNoSteps() {
        SyntheticWalker w = new SyntheticWalker(5);
        Counter c = new Counter(new PdrConfig());
        w.stand(60, 0, c);
        w.turn(Math.PI, 2, c);            // 原地转身也不算走路
        w.stand(5, 0, c);
        assertEquals(0, c.steps.size());
        assertTrue(c.detector.isStill(w.t, 1.0));
    }

    @Test
    public void singleJoltsAreIgnored() {
        PdrConfig cfg = new PdrConfig();
        StepDetector d = new StepDetector(cfg);
        List<StepDetector.Step> out = new ArrayList<StepDetector.Step>();
        long t = 0;
        long dt = 20_000_000L;
        for (int k = 0; k < 3; k++) {                      // 三次相隔 3 s 的晃动（比如拿起、放下手机）
            for (int i = 0; i < 150; i++) {
                t += dt;
                double bump = (i >= 50 && i < 65) ? 5 * Math.sin(Math.PI * (i - 50) / 15.0) : 0;
                d.update(t, 0, 0.3, 9.81 + bump, out);
            }
        }
        assertEquals(0, out.size());
        assertEquals(0, d.emitted());
    }

    @Test
    public void firstStepOfABoutIsConfirmedRetroactively() {
        PdrConfig cfg = new PdrConfig();
        SyntheticWalker w = new SyntheticWalker(6);
        Counter c = new Counter(cfg);
        w.stand(2, 0, c);
        w.walk(1.0 / w.stepHz * 1.2, 0, c);              // 只走了一步多一点
        w.stand(0.4, 0, c);
        assertEquals("第一步挂起，等待第二步", 0, c.steps.size());
        w.walk(3 / w.stepHz, 0, c);
        w.stand(2, 0, c);
        assertTrue(c.steps.size() >= 3);
        // 第一步的时间戳应该早于第二步，且间隔是 NaN（新一段行走的开始）
        assertTrue(Double.isNaN(c.steps.get(0).interval));
        assertTrue(c.steps.get(1).t > c.steps.get(0).t);
        assertEquals(1 / w.stepHz, c.steps.get(2).interval, 0.06);
    }

    @Test
    public void amplitudeGrowsWithStrongerSteps() {
        double[] bounces = {1.5, 2.5, 4.0};
        double prev = 0;
        for (double b : bounces) {
            SyntheticWalker w = new SyntheticWalker(7);
            w.bounce = b;
            Counter c = new Counter(new PdrConfig());
            w.walk(10, 0, c);
            double sum = 0;
            for (StepDetector.Step s : c.steps) sum += s.amplitude;
            double mean = sum / c.steps.size();
            assertTrue("幅值应随步伐力度增大：" + mean + " > " + prev, mean > prev);
            prev = mean;
        }
    }

    @Test
    public void survivesTimestampGapsAndDuplicates() {
        SyntheticWalker w = new SyntheticWalker(8);
        Counter c = new Counter(new PdrConfig());
        w.walk(5, 0, c);
        int before = c.steps.size();
        w.t += 3_000_000_000L;                              // 传感器停了 3 秒
        c.acc(w.t, 0, 0, 30);                               // 恢复后的第一帧是异常值
        c.acc(w.t, 0, 0, 9.8);                              // 重复时间戳
        w.stand(3, 0, c);
        assertEquals(before, c.steps.size());
        w.walk(5, 0, c);
        w.stand(2, 0, c);
        assertTrue(c.steps.size() - before >= 7);
    }
}
