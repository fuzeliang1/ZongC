package com.zongc.dw.util;

/** 数值小工具。 */
public final class Num {
    private Num() {}

    /** 保留 3 位小数，减小推送给前端的数据量。 */
    public static double r3(double v) {
        if (!Double.isFinite(v)) return 0;
        return Math.round(v * 1000.0) / 1000.0;
    }

    public static double r1(double v) {
        if (!Double.isFinite(v)) return 0;
        return Math.round(v * 10.0) / 10.0;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
