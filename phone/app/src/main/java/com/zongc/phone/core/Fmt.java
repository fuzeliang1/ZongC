package com.zongc.phone.core;

/**
 * 与语言区域无关的定点数格式化（JSON / CSV 都要求小数点是 '.'）。
 *
 * <p>比 {@code String.format} 快得多，适合每秒上百行的传感器日志。</p>
 */
public final class Fmt {
    private static final long[] POW10 = {1L, 10L, 100L, 1000L, 10000L, 100000L, 1000000L, 10000000L};

    private Fmt() {
    }

    /**
     * 追加保留 {@code decimals} 位小数的数字；非有限值（NaN / ±∞）追加 {@code nonFinite}。
     */
    public static StringBuilder fixed(StringBuilder sb, double v, int decimals, String nonFinite) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return sb.append(nonFinite);
        if (decimals < 0) decimals = 0;
        if (decimals >= POW10.length) decimals = POW10.length - 1;
        long scale = POW10[decimals];
        double scaled = Math.abs(v) * scale;
        if (scaled > 9.0e15) return sb.append(v);   // 超出 long 精度：退回默认格式
        long r = Math.round(scaled);
        if (v < 0 && r != 0) sb.append('-');
        sb.append(r / scale);
        if (decimals > 0) {
            sb.append('.');
            String frac = Long.toString(r % scale);
            for (int i = frac.length(); i < decimals; i++) sb.append('0');
            sb.append(frac);
        }
        return sb;
    }

    /** 用于 JSON：非有限值写成 null。 */
    public static String json(double v, int decimals) {
        return fixed(new StringBuilder(16), v, decimals, "null").toString();
    }

    /** 用于界面显示：非有限值显示为 "—"。 */
    public static String show(double v, int decimals) {
        return fixed(new StringBuilder(16), v, decimals, "—").toString();
    }
}
