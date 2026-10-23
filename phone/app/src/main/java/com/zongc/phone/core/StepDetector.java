package com.zongc.phone.core;

import java.util.List;

/**
 * 步伐检测。
 *
 * <pre>
 * |a| ──减去重力估计──▶ d ──两级一阶低通(≈3 Hz)──▶ f ──峰谷状态机──▶ 一步
 * </pre>
 *
 * <ul>
 *   <li>只用加速度模长，所以与手机的握持角度无关；</li>
 *   <li>峰值确认用回滞（下降超过 hyst 才算峰），回滞随最近一步的幅值自适应；</li>
 *   <li>峰谷差小于 {@link PdrConfig#minStepAmplitude}、或离上一步太近的，不算一步；</li>
 *   <li>新一段行走的第一步先挂起，等到第二步出现才一起确认，避免拿起手机时误计一步。</li>
 * </ul>
 */
public final class StepDetector {

    /** 检测到的一步。 */
    public static final class Step {
        /** 峰值时刻（与传感器时间戳同一时基，ns）。 */
        public final long t;
        /** 峰谷差（m/s²），用于 Weinberg 步长模型。 */
        public final double amplitude;
        /** 与上一步的间隔（s）；新一段行走的第一步为 NaN。 */
        public final double interval;

        Step(long t, double amplitude, double interval) {
            this.t = t;
            this.amplitude = amplitude;
            this.interval = interval;
        }
    }

    /** 判定"静止"的加速度波动均方根阈值（m/s²）。 */
    static final double STILL_RMS = 0.15;

    private static final int SEEK_PEAK = 0;
    private static final int SEEK_VALLEY = 1;

    private final PdrConfig cfg;

    private boolean init;
    private long lastT;
    private double gravity;
    private double f1;
    private double f2;
    private double energy;
    private long stillSince = -1;

    private int phase = SEEK_PEAK;
    private double runMax = Double.NEGATIVE_INFINITY;
    private long runMaxT;
    private double runMin = Double.POSITIVE_INFINITY;
    private double peak;
    private long peakT;

    private boolean hasLastStep;
    private long lastStepT;
    private double lastAmp = 2.0;
    private Step pending;
    private int emitted;
    private int rejected;

    public StepDetector(PdrConfig cfg) {
        this.cfg = cfg;
    }

    /** 清空所有状态（重新开始）。 */
    public void reset() {
        init = false;
        restartFilters();
        hasLastStep = false;
        lastAmp = 2.0;
        emitted = 0;
        rejected = 0;
    }

    private void restartFilters() {
        f1 = f2 = energy = 0;
        stillSince = -1;
        phase = SEEK_PEAK;
        runMax = Double.NEGATIVE_INFINITY;
        runMin = Double.POSITIVE_INFINITY;
        pending = null;
    }

    /**
     * 输入一帧加速度计数据（设备坐标系，含重力，m/s²）。
     *
     * @param t   时间戳（ns）
     * @param out 确认的步追加到这里（可能一次追加两步：挂起的第一步 + 当前步）
     * @return 本次追加的步数
     */
    public int update(long t, double ax, double ay, double az, List<Step> out) {
        double m = Math.sqrt(ax * ax + ay * ay + az * az);
        if (!init) {
            init = true;
            lastT = t;
            gravity = initialGravity(m);
            restartFilters();
            return 0;
        }
        double dt = (t - lastT) * 1e-9;
        if (dt <= 0) return 0;                 // 重复或乱序的时间戳
        lastT = t;
        if (dt > 0.5) {                        // 传感器停过一段时间：滤波器重新开始
            gravity = initialGravity(m);
            restartFilters();
            return 0;
        }

        gravity += (m - gravity) * alpha(dt, cfg.gravityTau);
        double d = m - gravity;
        double a = alpha(dt, 1.0 / (2 * Math.PI * cfg.lowpassHz));
        f1 += (d - f1) * a;
        f2 += (f1 - f2) * a;
        double f = f2;

        energy += (d * d - energy) * alpha(dt, 0.5);
        if (energy < STILL_RMS * STILL_RMS) {
            if (stillSince < 0) stillSince = t;
        } else {
            stillSince = -1;
        }

        int before = out.size();
        double hyst = Geo.clamp(0.25 * lastAmp, 0.2, 0.8);
        if (phase == SEEK_PEAK) {
            if (f > runMax) {
                runMax = f;
                runMaxT = t;
            }
            if (f < runMax - hyst) {
                if (runMax > 0.25 * cfg.minStepAmplitude) {
                    peak = runMax;
                    peakT = runMaxT;
                    phase = SEEK_VALLEY;
                    runMin = f;
                } else {
                    runMax = f;        // 太小的起伏不算峰，继续找
                    runMaxT = t;
                }
            }
        } else {
            if (f > peak) {            // 还没出现像样的谷就又冲得更高：峰值后移
                peak = f;
                peakT = t;
                runMin = f;
            } else {
                if (f < runMin) runMin = f;
                if (f > runMin + hyst) {
                    onValley(peak, peakT, runMin, out);
                    phase = SEEK_PEAK;
                    runMax = f;
                    runMaxT = t;
                }
            }
        }
        return out.size() - before;
    }

    private void onValley(double peakValue, long tPeak, double valley, List<Step> out) {
        double amp = peakValue - valley;
        if (amp < cfg.minStepAmplitude) {
            rejected++;
            return;
        }
        double interval = hasLastStep ? (tPeak - lastStepT) * 1e-9 : Double.NaN;
        if (!Double.isNaN(interval) && interval < cfg.minStepInterval) {
            rejected++;
            return;
        }
        boolean inBout = !Double.isNaN(interval) && interval <= cfg.maxStepInterval;
        Step s = new Step(tPeak, amp, inBout ? interval : Double.NaN);
        hasLastStep = true;
        lastStepT = tPeak;
        lastAmp = amp;
        if (inBout || !cfg.confirmFirstStep) {
            if (pending != null) {
                out.add(pending);
                emitted++;
                pending = null;
            }
            out.add(s);
            emitted++;
        } else {
            pending = s;               // 新一段行走的第一步：等下一步确认
        }
    }

    /** 重力估计的初值：第一帧明显异常（比如正好在晃动）时用标准重力。 */
    private static double initialGravity(double m) {
        return Math.abs(m - 9.81) < 2 ? m : 9.81;
    }

    static double alpha(double dt, double tau) {
        return tau <= 0 ? 1 : 1 - Math.exp(-dt / tau);
    }

    /** 已经静止（加速度几乎不波动）至少 {@code seconds} 秒。 */
    public boolean isStill(long t, double seconds) {
        return stillSince >= 0 && (t - stillSince) * 1e-9 >= seconds;
    }

    /** 最近 {@link PdrConfig#maxStepInterval} 秒内走过一步。 */
    public boolean isWalking(long t) {
        return hasLastStep && (t - lastStepT) * 1e-9 <= cfg.maxStepInterval;
    }

    /** 当前滤波后的信号（m/s²），用于显示和日志。 */
    public double signal() {
        return f2;
    }

    /** 当前估计的重力加速度模长（m/s²）。 */
    public double gravity() {
        return gravity;
    }

    public int emitted() {
        return emitted;
    }

    public int rejected() {
        return rejected;
    }
}
