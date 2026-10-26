package com.zongc.phone.core;

import java.util.Map;

/**
 * 行人航位推算（PDR）与手机车的全部可调参数。
 *
 * <p>App 把它序列化成一行 JSON 存在 SharedPreferences 里；单元测试直接 new 一个用默认值。</p>
 */
public final class PdrConfig {
    // ---------------------------------------------------------------- 步伐检测
    /** 加速度模长的低通截止频率（Hz）。走路步频 1.5~2.5 Hz。 */
    public double lowpassHz = 3.0;
    /** 估计重力（模长均值）用的时间常数（s）。 */
    public double gravityTau = 2.0;
    /** 一步的最小峰谷差（m/s²），小于它当作手抖。 */
    public double minStepAmplitude = 0.8;
    /** 两步之间的最短时间（s），对应最高步频约 3.5 Hz。 */
    public double minStepInterval = 0.28;
    /** 两步之间的最长时间（s），超过就认为停下了，下一步算新一段行走的第一步。 */
    public double maxStepInterval = 1.6;
    /** 新一段行走的第一步要等第二步出现才确认，过滤拿起手机等单次晃动。 */
    public boolean confirmFirstStep = true;

    // ---------------------------------------------------------------- 步长
    /** Weinberg 步长模型 L = K·(a_max − a_min)^(1/4) 的系数，可用"步长标定"自动求。 */
    public double weinbergK = 0.45;
    public double minStepLength = 0.25;
    public double maxStepLength = 1.2;

    // ---------------------------------------------------------------- 航向
    /** 静止时估计陀螺零偏。 */
    public boolean gyroBiasEstimation = true;
    /** 室外模式下磁力计修正航向的增益 k_p（1/s），越大越信磁力计。 */
    public double magGain = 0.1;
    /** 磁偏角（度，东偏为正）。上海约 −6.5°（偏西）。 */
    public double declinationDeg = -6.5;

    // ---------------------------------------------------------------- 卫星与磁力计
    /** 室外模式：用磁力计定北、用卫星定位（GPS/北斗）修正位置漂移。室内请关闭。 */
    public boolean outdoor = false;
    /** 精度差于此值（米）的卫星定位结果不参与融合。 */
    public double gnssMaxAccuracy = 25.0;

    // ---------------------------------------------------------------- 手机车
    /** 驾驶比例：人走 1 米，车沿路线开多少米（车速还受最高车速 1.5 m/s 限制）。人走得慢、想让车快些时调大。 */
    public double driveScale = 1.0;

    // ---------------------------------------------------------------- 模拟行走（桌面演示）
    public double simStepLength = 0.65;
    public double simCadenceHz = 1.8;

    public PdrConfig copy() {
        PdrConfig c = new PdrConfig();
        c.copyFrom(this);
        return c;
    }

    /** 复制另一份参数的全部字段。 */
    public void copyFrom(PdrConfig o) {
        lowpassHz = o.lowpassHz;
        gravityTau = o.gravityTau;
        minStepAmplitude = o.minStepAmplitude;
        minStepInterval = o.minStepInterval;
        maxStepInterval = o.maxStepInterval;
        confirmFirstStep = o.confirmFirstStep;
        weinbergK = o.weinbergK;
        minStepLength = o.minStepLength;
        maxStepLength = o.maxStepLength;
        gyroBiasEstimation = o.gyroBiasEstimation;
        magGain = o.magGain;
        declinationDeg = o.declinationDeg;
        outdoor = o.outdoor;
        gnssMaxAccuracy = o.gnssMaxAccuracy;
        driveScale = o.driveScale;
        simStepLength = o.simStepLength;
        simCadenceHz = o.simCadenceHz;
    }

    public String toJson() {
        return new Json.Obj()
                .put("lowpassHz", lowpassHz, 4)
                .put("gravityTau", gravityTau, 4)
                .put("minStepAmplitude", minStepAmplitude, 4)
                .put("minStepInterval", minStepInterval, 4)
                .put("maxStepInterval", maxStepInterval, 4)
                .put("confirmFirstStep", confirmFirstStep)
                .put("weinbergK", weinbergK, 6)
                .put("minStepLength", minStepLength, 4)
                .put("maxStepLength", maxStepLength, 4)
                .put("gyroBiasEstimation", gyroBiasEstimation)
                .put("magGain", magGain, 4)
                .put("declinationDeg", declinationDeg, 3)
                .put("outdoor", outdoor)
                .put("gnssMaxAccuracy", gnssMaxAccuracy, 2)
                .put("driveScale", driveScale, 4)
                .put("simStepLength", simStepLength, 4)
                .put("simCadenceHz", simCadenceHz, 4)
                .toString();
    }

    /** 从 JSON 读取参数；缺失或不合理的字段保留当前值。 */
    public static PdrConfig fromJson(String json) {
        PdrConfig c = new PdrConfig();
        if (json == null || json.isEmpty()) return c;
        try {
            c.apply(Json.parseObject(json));
        } catch (IllegalArgumentException ignored) {
            // 保存的内容损坏：使用默认值
        }
        return c;
    }

    private void apply(Map<String, Object> m) {
        if (m == null) return;
        lowpassHz = pos(m, "lowpassHz", lowpassHz, 0.5, 20);
        gravityTau = pos(m, "gravityTau", gravityTau, 0.2, 60);
        minStepAmplitude = pos(m, "minStepAmplitude", minStepAmplitude, 0.05, 20);
        minStepInterval = pos(m, "minStepInterval", minStepInterval, 0.1, 2);
        maxStepInterval = pos(m, "maxStepInterval", maxStepInterval, 0.5, 5);
        confirmFirstStep = Json.bool(m, "confirmFirstStep", confirmFirstStep);
        weinbergK = pos(m, "weinbergK", weinbergK, 0.05, 2);
        minStepLength = pos(m, "minStepLength", minStepLength, 0.05, 2);
        maxStepLength = pos(m, "maxStepLength", maxStepLength, 0.2, 3);
        gyroBiasEstimation = Json.bool(m, "gyroBiasEstimation", gyroBiasEstimation);
        magGain = pos(m, "magGain", magGain, 0, 5);
        declinationDeg = pos(m, "declinationDeg", declinationDeg, -90, 90);
        outdoor = Json.bool(m, "outdoor", outdoor);
        gnssMaxAccuracy = pos(m, "gnssMaxAccuracy", gnssMaxAccuracy, 1, 1000);
        // 旧版本把"比例尺"存为 mapScale（人走 1 米 = 地图上几米），含义相同，接着用
        driveScale = pos(m, m.containsKey("driveScale") ? "driveScale" : "mapScale", driveScale, 0.1, 10);
        simStepLength = pos(m, "simStepLength", simStepLength, 0.1, 3);
        simCadenceHz = pos(m, "simCadenceHz", simCadenceHz, 0.2, 5);
    }

    private static double pos(Map<String, Object> m, String key, double def, double lo, double hi) {
        double v = Json.num(m, key, def);
        return Double.isNaN(v) || v < lo || v > hi ? def : v;
    }
}
