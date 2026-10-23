package com.zongc.phone.core;

import java.util.Random;

/**
 * 模拟"人拿着手机走路"时手机传感器的读数，用于单元测试和电脑上的端到端仿真。
 *
 * <p>世界坐标系为东-北-天（ENU）。手机姿态 R = Rz(ψ − π/2)·Rx(俯仰)·Ry(横滚)：
 * ψ 为机头水平朝向（逆时针从正东量起），俯仰为机头抬起的角度（拿着看屏幕时约 30°~40°）。
 * 设备坐标系与安卓一致：x 向右、y 向机头、z 垂直屏幕向外。</p>
 *
 * <ul>
 *   <li>加速度计（比力）= Rᵀ·(水平晃动 + (0, 0, g + 竖直起伏))；</li>
 *   <li>陀螺仪 = Rᵀ·(0, 0, ψ̇) + 俯仰晃动角速度 + 零偏；</li>
 *   <li>磁力计 = Rᵀ·B，B 为当地地磁场（上海：水平约 33.6 µT，竖直向下约 35.4 µT，磁偏角约 −6.5°）。</li>
 * </ul>
 */
public final class SyntheticWalker {
    public static final double G = 9.81;

    /** 传感器数据的接收者（PdrEngine 或日志）。 */
    public interface Sink {
        void acc(long t, double x, double y, double z);

        void gyro(long t, double x, double y, double z);

        void mag(long t, double x, double y, double z);
    }

    // ---- 环境与手机参数
    public double bHorizontal = 33.6;
    public double bVertical = 35.4;
    public double declinationDeg = -6.5;
    public double pitchDeg = 35;
    public double rollDeg = 0;
    public double swayDeg = 2.0;
    public double sampleHz = 50;
    public double accNoise = 0.12;
    public double gyroNoise = 0.008;
    public double magNoise = 0.4;
    public double gyroBiasZ = 0;

    // ---- 步态参数
    public double stepHz = 1.8;
    public double bounce = 2.5;
    public double stepLength = 0.62;

    // ---- 真实状态
    public long t = 1_000_000_000L;
    public double yaw = Math.PI / 2;
    public double x;
    public double y;
    public int trueSteps;

    private final Random rnd;
    private double phase;

    public SyntheticWalker(long seed) {
        rnd = new Random(seed);
    }

    /** 站着不动 seconds 秒（可以原地转身）。 */
    public void stand(double seconds, double yawRate, Sink sink) {
        run(seconds, false, yawRate, sink);
    }

    /** 走 seconds 秒，同时以 yawRate 转弯。 */
    public void walk(double seconds, double yawRate, Sink sink) {
        run(seconds, true, yawRate, sink);
    }

    /** 走 n 步（直线）。 */
    public void walkSteps(int n, Sink sink) {
        walk(n / stepHz, 0, sink);
    }

    /** 原地转身 angle（rad），用 duration 秒。 */
    public void turn(double angle, double duration, Sink sink) {
        stand(duration, angle / duration, sink);
    }

    private void run(double seconds, boolean walking, double yawRate, Sink sink) {
        double dt = 1.0 / sampleHz;
        int n = (int) Math.round(seconds * sampleHz);
        for (int i = 0; i < n; i++) {
            t += (long) (dt * 1e9);
            yaw += yawRate * dt;
            double az = 0, af = 0, pitchRate = 0;
            double pitch = Math.toRadians(pitchDeg);
            if (walking) {
                double before = phase;
                phase += 2 * Math.PI * stepHz * dt;
                if (Math.floor(phase / (2 * Math.PI)) > Math.floor(before / (2 * Math.PI))) trueSteps++;
                az = bounce * Math.sin(phase);
                af = 0.4 * bounce * Math.cos(phase);
                double sway = Math.toRadians(swayDeg);
                pitch += sway * Math.sin(phase / 2);
                pitchRate = sway * Math.cos(phase / 2) * Math.PI * stepHz;
                double v = stepLength * stepHz;
                x += v * Math.cos(yaw) * dt;
                y += v * Math.sin(yaw) * dt;
            }
            double[][] r = rotation(yaw, pitch, Math.toRadians(rollDeg));
            double[] fw = {af * Math.cos(yaw), af * Math.sin(yaw), G + az};
            double[] acc = mulT(r, fw);
            double[] gyr = mulT(r, new double[]{0, 0, yawRate});
            gyr[0] += pitchRate;
            double d = Math.toRadians(declinationDeg);
            double[] bw = {bHorizontal * Math.sin(d), bHorizontal * Math.cos(d), -bVertical};
            double[] mag = mulT(r, bw);
            sink.acc(t, acc[0] + n(accNoise), acc[1] + n(accNoise), acc[2] + n(accNoise));
            sink.gyro(t, gyr[0] + n(gyroNoise), gyr[1] + n(gyroNoise), gyr[2] + gyroBiasZ + n(gyroNoise));
            sink.mag(t, mag[0] + n(magNoise), mag[1] + n(magNoise), mag[2] + n(magNoise));
        }
    }

    private double n(double sigma) {
        return sigma * rnd.nextGaussian();
    }

    /** R = Rz(yaw − π/2)·Rx(pitch)·Ry(roll)。 */
    static double[][] rotation(double yaw, double pitch, double roll) {
        double a = yaw - Math.PI / 2;
        double[][] rz = {{Math.cos(a), -Math.sin(a), 0}, {Math.sin(a), Math.cos(a), 0}, {0, 0, 1}};
        double[][] rx = {{1, 0, 0}, {0, Math.cos(pitch), -Math.sin(pitch)}, {0, Math.sin(pitch), Math.cos(pitch)}};
        double[][] ry = {{Math.cos(roll), 0, Math.sin(roll)}, {0, 1, 0}, {-Math.sin(roll), 0, Math.cos(roll)}};
        return mul(mul(rz, rx), ry);
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++) c[i][j] += a[i][k] * b[k][j];
        return c;
    }

    /** Rᵀ·v（世界 → 设备）。 */
    static double[] mulT(double[][] r, double[] v) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++)
            for (int k = 0; k < 3; k++) out[i] += r[k][i] * v[k];
        return out;
    }

    /** 把数据喂给 PdrEngine 的 Sink。 */
    public static Sink into(final PdrEngine e) {
        return new Sink() {
            @Override
            public void acc(long t, double x, double y, double z) {
                e.onAccel(t, x, y, z);
            }

            @Override
            public void gyro(long t, double x, double y, double z) {
                e.onGyro(t, x, y, z);
            }

            @Override
            public void mag(long t, double x, double y, double z) {
                e.onMag(t, x, y, z);
            }
        };
    }
}
