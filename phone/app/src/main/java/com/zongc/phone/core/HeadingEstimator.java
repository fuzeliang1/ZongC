package com.zongc.phone.core;

/**
 * 航向估计：陀螺仪积分为主，磁力计（可选）慢速修正。
 *
 * <h3>为什么用 ω·up</h3>
 * 人拿着手机走路时，手机是倾斜的。我们关心的是"绕竖直轴转了多少"，所以把陀螺仪测到的
 * 角速度向量 ω（设备坐标系）投影到"竖直向上"的单位向量 up（同样在设备坐标系，由重力得到）上：
 * <pre>  dψ/dt = (ω − b)·up        b 为陀螺零偏，静止时估计</pre>
 * 这样无论手机平放还是斜拿，转身多少度航向就变多少度。
 *
 * <h3>磁力计航向（室外模式）</h3>
 * <pre>
 *   E = normalize(m × up)   东（设备坐标系）
 *   N = up × E              北
 *   f = 手机"前方"在水平面上的投影（默认取机头 +y；手机竖起来时改用背面摄像头方向 −z）
 *   ψ_mag = atan2(f·N, f·E) − 磁偏角      （逆时针、从正东量起，与地图一致）
 * </pre>
 * 室外模式下用 PI 互补滤波把陀螺漂移拉回来（e = wrap(ψ_mag − ψ)）：
 * <pre>  ψ ← ψ + k_p·e·dt        b_mag ← b_mag − k_i·e·dt    （k_i = k_p²/2）</pre>
 * 积分项 b_mag 学习剩余的陀螺零偏，否则走路时（无法静止估计零偏）会留下 零偏/k_p 的稳态误差。
 * 室内钢筋水泥对磁场干扰大，所以室内默认只用陀螺仪。手机没有陀螺仪时退化为只用磁力计。
 *
 * <p>内部航向 ψ 不做 ±π 折叠（连续累加），便于按时间插值查询历史航向。</p>
 */
public final class HeadingEstimator {
    private static final int HISTORY = 512;

    private final PdrConfig cfg;

    // 竖直向上的单位向量（设备坐标系）
    private double ux, uy, uz = 1;
    private boolean upValid;
    private long gravityT;
    private boolean gravitySeen;
    private double lax, lay, laz;
    private boolean accInit;
    private long accT;

    // 陀螺
    private double bx, by, bz;
    private boolean gyroInit;
    private boolean gyroSeen;
    private long gyroT;
    private double yaw;
    private double rate;
    private boolean yawInit;

    // 磁力计
    private boolean magCorrection;
    /** 由磁力计修正学到的竖直方向陀螺零偏（rad/s）。 */
    private double magBias;
    private double magYaw = Double.NaN;
    private double magNorm = Double.NaN;
    private long magT;
    private boolean magSeen;

    // 航向历史（时间, 连续航向）
    private final long[] hT = new long[HISTORY];
    private final double[] hY = new double[HISTORY];
    private int hCount;
    private int hNext;

    public HeadingEstimator(PdrConfig cfg) {
        this.cfg = cfg;
    }

    /** 是否用磁力计修正航向（室外模式）。 */
    public void setMagCorrection(boolean on) {
        magCorrection = on;
    }

    // ---------------------------------------------------------------- 输入

    /** Android 的 TYPE_GRAVITY（已融合的重力向量），有就优先用。 */
    public void onGravity(long t, double gx, double gy, double gz) {
        if (setUp(gx, gy, gz)) {
            gravitySeen = true;
            gravityT = t;
        }
    }

    /** 加速度计：没有 TYPE_GRAVITY 时用它的低通值当重力方向。 */
    public void onAccel(long t, double ax, double ay, double az) {
        if (!accInit) {
            accInit = true;
            lax = ax;
            lay = ay;
            laz = az;
        } else {
            double dt = (t - accT) * 1e-9;
            if (dt > 0) {
                double a = dt > 0.5 ? 1 : StepDetector.alpha(dt, 0.3);
                lax += (ax - lax) * a;
                lay += (ay - lay) * a;
                laz += (az - laz) * a;
            }
        }
        accT = t;
        if (!gravitySeen || (t - gravityT) * 1e-9 > 0.5) setUp(lax, lay, laz);
    }

    /**
     * 陀螺仪（rad/s，设备坐标系）。
     *
     * @param still 手机此刻是否静止（由步伐检测器判断），静止时更新零偏估计
     */
    public void onGyro(long t, double wx, double wy, double wz, boolean still) {
        gyroSeen = true;
        if (!gyroInit) {
            gyroInit = true;
            gyroT = t;
            record(t);
            return;
        }
        double dt = (t - gyroT) * 1e-9;
        if (dt <= 0) return;
        gyroT = t;
        if (dt > 0.5) {               // 中断过：这段时间转了多少不知道，只能跳过
            record(t);
            return;
        }
        if (cfg.gyroBiasEstimation && still && Math.sqrt(wx * wx + wy * wy + wz * wz) < 0.15) {
            // 静止时陀螺读数就是零偏。总零偏 = b·up + magBias，所以 b 只学去掉 magBias 后剩下的部分
            double a = StepDetector.alpha(dt, 4.0);
            double mux = upValid ? magBias * ux : 0, muy = upValid ? magBias * uy : 0;
            double muz = upValid ? magBias * uz : magBias;
            bx += (wx - mux - bx) * a;
            by += (wy - muy - by) * a;
            bz += (wz - muz - bz) * a;
        }
        double cx = wx - bx, cy = wy - by, cz = wz - bz;
        rate = (upValid ? cx * ux + cy * uy + cz * uz : cz) - magBias;
        yaw += rate * dt;

        if (magCorrection && magFresh(t)) {
            // PI 互补滤波：比例项把航向拉向磁北，积分项学习剩余的陀螺零偏（否则会有 零偏/k 的稳态误差）
            double err = Geo.diff(magYaw, yaw);
            double kp = cfg.magGain;
            double ki = kp * kp / 2;                               // 阻尼比约 0.7
            boolean suspicious = Math.abs(err) > Math.toRadians(30); // 偏差太大多半是磁干扰：少信一点
            if (suspicious) kp *= 0.25;
            yaw += Geo.clamp(kp * dt, 0, 1) * err;
            if (!suspicious) magBias = Geo.clamp(magBias - ki * err * dt, -0.05, 0.05);
        }
        record(t);
    }

    /** 磁力计（µT，设备坐标系）。 */
    public void onMag(long t, double mx, double my, double mz) {
        long prevT = magT;
        boolean hadPrev = magSeen;
        magSeen = true;
        magT = t;
        magNorm = Math.sqrt(mx * mx + my * my + mz * mz);
        magYaw = upValid ? computeMagYaw(mx, my, mz) : Double.NaN;
        if (Double.isNaN(magYaw)) return;

        if (!yawInit) {
            // 第一次拿到磁航向：用它当初始航向，使默认情况下"地图向上 ≈ 正北"
            yawInit = true;
            shiftYaw(Geo.diff(magYaw, yaw));
            return;
        }
        if (!gyroSeen && hadPrev) {
            // 没有陀螺仪的手机：航向直接跟随磁力计（低通）
            double dt = (t - prevT) * 1e-9;
            if (dt > 0) {
                double a = dt > 0.5 ? 1 : StepDetector.alpha(dt, 0.5);
                yaw += a * Geo.diff(magYaw, yaw);
                rate = 0;
                record(t);
            }
        }
    }

    private double computeMagYaw(double mx, double my, double mz) {
        if (magNorm < 15 || magNorm > 100) return Double.NaN;     // 地磁场约 25~65 µT
        // E = m × up
        double ex = my * uz - mz * uy;
        double ey = mz * ux - mx * uz;
        double ez = mx * uy - my * ux;
        double en = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (en < 0.1 * magNorm) return Double.NaN;                // 磁场几乎竖直：无法定北
        ex /= en;
        ey /= en;
        ez /= en;
        // N = up × E
        double nx = uy * ez - uz * ey;
        double ny = uz * ex - ux * ez;
        double nz = ux * ey - uy * ex;
        double[] f = forward();
        double magneticYaw = Math.atan2(f[0] * nx + f[1] * ny + f[2] * nz, f[0] * ex + f[1] * ey + f[2] * ez);
        return Geo.wrap(magneticYaw - Math.toRadians(cfg.declinationDeg));
    }

    /** 手机"前方"在水平面上的投影（设备坐标系，未归一化）。 */
    private double[] forward() {
        // 机头方向 +y 去掉竖直分量
        double d = uy;
        double fx = -d * ux, fy = 1 - d * uy, fz = -d * uz;
        if (fx * fx + fy * fy + fz * fz > 0.09) return new double[]{fx, fy, fz};
        // 手机接近竖直：改用背面（摄像头）方向 −z
        d = -uz;
        return new double[]{-d * ux, -d * uy, -1 - d * uz};
    }

    private boolean setUp(double x, double y, double z) {
        double n = Math.sqrt(x * x + y * y + z * z);
        if (n < 1e-3 || Double.isNaN(n)) return false;
        ux = x / n;
        uy = y / n;
        uz = z / n;
        upValid = true;
        return true;
    }

    private boolean magFresh(long t) {
        return magSeen && !Double.isNaN(magYaw) && (t - magT) * 1e-9 < 0.5;
    }

    private void record(long t) {
        hT[hNext] = t;
        hY[hNext] = yaw;
        hNext = (hNext + 1) % HISTORY;
        if (hCount < HISTORY) hCount++;
    }

    // ---------------------------------------------------------------- 输出

    /** 当前航向（内部坐标系 P，已折叠到 (-π, π]）。 */
    public double yaw() {
        return Geo.wrap(yaw);
    }

    /** 时刻 t 的航向（线性插值；早于历史记录时取最早一条）。 */
    public double yawAt(long t) {
        if (hCount == 0) return Geo.wrap(yaw);
        int newest = (hNext - 1 + HISTORY) % HISTORY;
        if (t >= hT[newest]) return Geo.wrap(yaw);
        int idx = newest;
        for (int k = 1; k < hCount; k++) {
            int older = (newest - k + HISTORY) % HISTORY;
            if (hT[older] <= t) {
                long t0 = hT[older], t1 = hT[idx];
                double w = t1 > t0 ? (double) (t - t0) / (t1 - t0) : 1;
                return Geo.wrap(hY[older] + w * (hY[idx] - hY[older]));
            }
            idx = older;
        }
        return Geo.wrap(hY[idx]);
    }

    /** 整体平移航向（换参考系时用），历史记录一起平移。 */
    public void shiftYaw(double delta) {
        yaw += delta;
        for (int k = 0; k < hCount; k++) hY[k] += delta;
    }

    /** 最近一次去零偏后的竖直角速度（rad/s）。 */
    public double rate() {
        return rate;
    }

    /** 磁力计给出的地理航向（rad，逆时针从正东量起）；没有或不可信时为 NaN。 */
    public double magYaw() {
        return magYaw;
    }

    public double magNorm() {
        return magNorm;
    }

    public boolean hasGyro() {
        return gyroSeen;
    }

    public boolean hasMag() {
        return magSeen;
    }

    /** 陀螺零偏在竖直方向上的分量（rad/s，静止估计 + 磁力计修正学到的部分），用于显示。 */
    public double biasUp() {
        return (upValid ? bx * ux + by * uy + bz * uz : bz) + magBias;
    }

    /** 手机相对水平面的倾角（度）：平放为 0，竖直为 90。 */
    public double tiltDeg() {
        return upValid ? Math.toDegrees(Math.acos(Geo.clamp(uz, -1, 1))) : Double.NaN;
    }
}
