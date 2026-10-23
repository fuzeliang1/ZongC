package com.zongc.phone.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机位姿估计的总装：步伐检测 + 步长模型 + 航向 + 坐标换算 + 卫星融合 + 输出平滑。
 *
 * <pre>
 *  加速度 ─▶ StepDetector ─▶ 一步(时刻, 峰谷差) ─▶ 步长 L = K·Δa^¼
 *  陀螺/重力/磁力 ─▶ HeadingEstimator ─▶ 航向 ψ(t)
 *  每一步：p_P += L·(cos ψ, sin ψ)                      （内部坐标系 P，真实米）
 *  室外：卫星定位 → 局部东北坐标 → 卡尔曼增益融合进 p_P
 *  输出：p_M = MapFrame(p_P) → 一阶平滑 → 上报数字世界
 * </pre>
 *
 * <p>所有公开方法都是 synchronized 的：传感器线程写，界面线程和网络线程读。
 * 时间参数一律是纳秒，与 Android 传感器时间戳同一时基（SystemClock.elapsedRealtimeNanos）。</p>
 */
public final class PdrEngine {
    private final PdrConfig cfg;
    private final StepDetector detector;
    private final HeadingEstimator heading;
    private final MapFrame frame = new MapFrame();
    private final List<StepDetector.Step> stepBuf = new ArrayList<StepDetector.Step>();

    // 内部坐标系 P 中的位置（真实米）
    private double px;
    private double py;

    // 步伐统计
    private int steps;
    private double distance;
    private double lastStepLength = Double.NaN;
    private double cadenceHz;
    private double speed;
    private boolean anyStep;
    private long lastStepT;

    // 输出平滑
    private boolean outInit;
    private double outX;
    private double outY;
    private long outT;

    // 卫星定位融合
    private static final int REF_FIXES = 5;
    private boolean gnssRef;
    private int refFixes;
    private double refSumX;
    private double refSumY;
    private double lat0;
    private double lon0;
    private double gnssOffX;
    private double gnssOffY;
    private double posVar = 1;
    private double fixLat = Double.NaN;
    private double fixLon = Double.NaN;
    private double fixAcc = Double.NaN;
    private long fixT;
    private boolean fixSeen;
    private boolean fixUsed;
    private boolean pendingMagSnap;

    // 步长标定
    private boolean calibrating;
    private int calibSteps;
    private double calibFactorSum;
    private double calibDistance;

    // 模拟行走
    private boolean simWalk;
    private long simNextT;

    public PdrEngine(PdrConfig config) {
        this.cfg = config.copy();
        this.detector = new StepDetector(cfg);
        this.heading = new HeadingEstimator(cfg);
        heading.setMagCorrection(cfg.outdoor);
        frame.setScaleKeeping(0, 0, cfg.mapScale);
    }

    // ================================================================ 传感器输入

    public synchronized void onAccel(long t, double ax, double ay, double az) {
        heading.onAccel(t, ax, ay, az);
        stepBuf.clear();
        detector.update(t, ax, ay, az, stepBuf);
        for (int i = 0; i < stepBuf.size(); i++) onDetectedStep(stepBuf.get(i));
    }

    public synchronized void onGyro(long t, double wx, double wy, double wz) {
        heading.onGyro(t, wx, wy, wz, detector.isStill(t, 0.8));
    }

    public synchronized void onGravity(long t, double gx, double gy, double gz) {
        heading.onGravity(t, gx, gy, gz);
    }

    public synchronized void onMag(long t, double mx, double my, double mz) {
        heading.onMag(t, mx, my, mz);
        if (pendingMagSnap) snapToMagnetic();
    }

    /**
     * 卫星定位结果（只在室外模式下参与融合）。
     *
     * @param accuracy 水平精度（米，68% 置信半径）
     */
    public synchronized void onLocation(long t, double lat, double lon, double accuracy) {
        fixLat = lat;
        fixLon = lon;
        fixAcc = accuracy;
        fixT = t;
        fixSeen = true;
        fixUsed = false;
        if (!cfg.outdoor || simWalk) return;
        if (!(accuracy > 0) || accuracy > cfg.gnssMaxAccuracy) return;
        if (!gnssRef) {
            // 第一次可用的定位：以它为局部东北坐标原点
            gnssRef = true;
            lat0 = lat;
            lon0 = lon;
            refFixes = 0;
            refSumX = 0;
            refSumY = 0;
        }
        double[] en = Geo.enu(lat0, lon0, lat, lon);
        if (refFixes < REF_FIXES) {
            // 前几次定位只用来确定"卫星坐标 ↔ PDR 坐标"的偏移（取平均，减小单次定位误差的影响）
            refSumX += px - en[0];
            refSumY += py - en[1];
            refFixes++;
            gnssOffX = refSumX / refFixes;
            gnssOffY = refSumY / refFixes;
            posVar = accuracy * accuracy / refFixes;
            fixUsed = true;
            return;
        }
        double zx = en[0] + gnssOffX;
        double zy = en[1] + gnssOffY;
        double r = accuracy * accuracy;
        double k = posVar / (posVar + r);       // 标量卡尔曼增益
        px += k * (zx - px);
        py += k * (zy - py);
        posVar = Math.max(0.25, (1 - k) * posVar);
        fixUsed = true;
    }

    private void onDetectedStep(StepDetector.Step s) {
        if (simWalk) return;                     // 模拟行走时忽略真实步伐
        double factor = Math.pow(s.amplitude, 0.25);
        double len = Geo.clamp(cfg.weinbergK * factor, cfg.minStepLength, cfg.maxStepLength);
        if (calibrating) {
            calibSteps++;
            calibFactorSum += factor;
            calibDistance += len;
        }
        advance(s.t, len, heading.yawAt(s.t), s.interval);
    }

    private void advance(long t, double len, double yawP, double interval) {
        px += len * Math.cos(yawP);
        py += len * Math.sin(yawP);
        steps++;
        distance += len;
        lastStepLength = len;
        if (interval > 0) {
            double c = 1 / interval;
            cadenceHz = cadenceHz == 0 ? c : cadenceHz + 0.3 * (c - cadenceHz);
        } else if (cadenceHz == 0) {
            cadenceHz = 1.8;
        }
        speed = len * cadenceHz;
        anyStep = true;
        lastStepT = t;
        posVar += 0.05 + 0.0625 * len * len;     // 每走一步位置不确定度增加（用于卫星融合）
    }

    // ================================================================ 输出

    /** 计算并返回当前状态（顺便推进模拟行走与输出平滑）。 */
    public synchronized PdrState state(long now) {
        updateSimWalk(now);
        double[] m = frame.toMap(px, py);
        if (!outInit) {
            outInit = true;
            outX = m[0];
            outY = m[1];
            outT = now;
        } else {
            double dt = (now - outT) * 1e-9;
            if (dt > 0) {
                double a = cfg.smoothingTau <= 0 ? 1 : 1 - Math.exp(-dt / cfg.smoothingTau);
                outX += (m[0] - outX) * a;
                outY += (m[1] - outY) * a;
                outT = now;
            }
        }
        PdrState s = new PdrState();
        s.t = now;
        s.x = outX;
        s.y = outY;
        s.rawX = m[0];
        s.rawY = m[1];
        s.theta = frame.toMapHeading(heading.yaw());
        s.walking = anyStep && (now - lastStepT) * 1e-9 <= cfg.maxStepInterval;
        s.v = s.walking ? speed * frame.scale() : 0;
        s.w = heading.rate();
        s.px = px;
        s.py = py;
        s.yawP = heading.yaw();
        s.steps = steps;
        s.distance = distance;
        s.stepLength = lastStepLength;
        s.cadence = s.walking ? cadenceHz * 60 : 0;
        s.accSignal = detector.signal();
        s.simWalk = simWalk;
        s.outdoor = cfg.outdoor;
        s.calibrating = calibrating;
        s.calibSteps = calibSteps;
        s.calibDistance = calibDistance;
        s.weinbergK = cfg.weinbergK;
        s.scale = frame.scale();
        s.rotation = frame.rotation();
        s.hasGyro = heading.hasGyro();
        s.hasMag = heading.hasMag();
        s.magYaw = heading.magYaw();
        s.magNorm = heading.magNorm();
        s.tiltDeg = heading.tiltDeg();
        s.gyroBiasUp = heading.biasUp();
        if (fixSeen) {
            s.gnssLat = fixLat;
            s.gnssLon = fixLon;
            s.gnssAccuracy = fixAcc;
            s.gnssAge = Math.max(0, (now - fixT) * 1e-9);
            s.gnssUsed = fixUsed;
        }
        s.posStd = cfg.outdoor && gnssRef ? Math.sqrt(posVar) : Double.NaN;
        return s;
    }

    private void updateSimWalk(long now) {
        if (!simWalk) return;
        long period = (long) (1e9 / cfg.simCadenceHz);
        if (now - simNextT > 2_000_000_000L) simNextT = now;   // 很久没调用：不要一次补很多步
        while (simNextT <= now) {
            advance(simNextT, cfg.simStepLength, heading.yaw(), 1 / cfg.simCadenceHz);
            simNextT += period;
        }
    }

    // ================================================================ 标定与设置

    /** 把"我现在的位置"设为地图上的 (mx, my)。 */
    public synchronized void setPosition(double mx, double my) {
        frame.anchor(px, py, mx, my);
        outInit = false;
    }

    /** 我现在（手机机头）正对着地图上 thetaM 方向。 */
    public synchronized void alignHeading(double thetaM) {
        frame.setRotationKeeping(px, py, thetaM - heading.yaw());
    }

    /** 我现在正对着地图上的点 (mx, my)。 */
    public synchronized void faceToward(double mx, double my) {
        double[] cur = frame.toMap(px, py);
        if (Math.hypot(mx - cur[0], my - cur[1]) < 1e-6) return;
        alignHeading(Math.atan2(my - cur[1], mx - cur[0]));
    }

    /** App 重启后恢复上次的地图位置与旋转角（比例尺在参数里）。 */
    public synchronized void restore(double mx, double my, double rotation) {
        if (Double.isNaN(mx) || Double.isNaN(my) || Double.isInfinite(mx) || Double.isInfinite(my)) return;
        frame.anchor(px, py, mx, my);
        if (!Double.isNaN(rotation) && !Double.isInfinite(rotation)) frame.setRotationKeeping(px, py, rotation);
        outInit = false;
    }

    /** 应用新参数（比例尺、室内/室外模式等的切换在这里处理，保证地图位置不跳变）。 */
    public synchronized void applyConfig(PdrConfig next) {
        boolean wasOutdoor = cfg.outdoor;
        double oldScale = cfg.mapScale;
        cfg.copyFrom(PdrConfig.fromJson(next.toJson()));   // 经过一次序列化：顺便检查取值范围
        if (cfg.mapScale != oldScale) {
            frame.setScaleKeeping(px, py, cfg.mapScale);
            outInit = false;
        }
        if (cfg.outdoor != wasOutdoor) {
            heading.setMagCorrection(cfg.outdoor);
            gnssRef = false;                       // 下一次可用定位重新建立参考点
            pendingMagSnap = cfg.outdoor;
            if (pendingMagSnap) snapToMagnetic();
        }
    }

    /** 当前参数的副本（标定会修改 K）。 */
    public synchronized PdrConfig config() {
        return cfg.copy();
    }

    /**
     * 进入室外模式时把内部航向一步对齐到磁北，同时反向修改地图旋转角，
     * 使地图上的朝向和位置都不跳变。磁力计暂时不可用时等它可用再做。
     */
    private void snapToMagnetic() {
        double mag = heading.magYaw();
        if (Double.isNaN(mag)) return;
        double delta = Geo.diff(mag, heading.yaw());
        heading.shiftYaw(delta);
        frame.setRotationKeeping(px, py, frame.rotation() - delta);
        pendingMagSnap = false;
    }

    /** 开始步长标定：之后沿直线走一段已知距离。 */
    public synchronized void startCalibration() {
        calibrating = true;
        calibSteps = 0;
        calibFactorSum = 0;
        calibDistance = 0;
    }

    /**
     * 结束步长标定。
     *
     * @param realMeters 实际走过的距离（米）
     * @return 新的 K；步数太少或结果不合理时返回 NaN（K 保持不变）
     */
    public synchronized double finishCalibration(double realMeters) {
        calibrating = false;
        if (calibSteps < 4 || calibFactorSum <= 0 || !(realMeters > 0)) return Double.NaN;
        double k = realMeters / calibFactorSum;
        if (k < 0.1 || k > 1.5) return Double.NaN;
        cfg.weinbergK = k;
        return k;
    }

    public synchronized void cancelCalibration() {
        calibrating = false;
    }

    /** 模拟行走（桌面演示）：按设定步频沿手机当前朝向自动"走路"，转动手机即可转向。 */
    public synchronized void setSimWalk(boolean on, long now) {
        if (on && !simWalk) simNextT = now;
        simWalk = on;
    }

    /** 步数、距离清零（位置不变）。 */
    public synchronized void resetCounters() {
        steps = 0;
        distance = 0;
    }
}
