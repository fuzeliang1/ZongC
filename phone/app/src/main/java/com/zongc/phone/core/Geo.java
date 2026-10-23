package com.zongc.phone.core;

/** 角度与经纬度的小工具。角度一律用弧度，逆时针为正（数学习惯，与数字世界一致）。 */
public final class Geo {
    /** 地球平均半径（米）。 */
    public static final double EARTH_RADIUS = 6371008.8;

    private Geo() {
    }

    /** 把角度规范到 (-π, π]。 */
    public static double wrap(double a) {
        if (Double.isNaN(a) || Double.isInfinite(a)) return a;
        a = a % (2 * Math.PI);
        if (a > Math.PI) a -= 2 * Math.PI;
        else if (a <= -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /** 角度差 a - b，结果在 (-π, π]。 */
    public static double diff(double a, double b) {
        return wrap(a - b);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 经纬度 → 以 (lat0, lon0) 为原点的局部东-北坐标（米）。
     *
     * <p>等距圆柱近似，几公里范围内误差小于 0.1%，对校园尺度完全够用。</p>
     *
     * @return {east, north}
     */
    public static double[] enu(double lat0, double lon0, double lat, double lon) {
        double latMid = Math.toRadians((lat0 + lat) / 2);
        double dLon = Math.toRadians(lon - lon0);
        if (dLon > Math.PI) dLon -= 2 * Math.PI;
        else if (dLon < -Math.PI) dLon += 2 * Math.PI;
        double east = dLon * Math.cos(latMid) * EARTH_RADIUS;
        double north = Math.toRadians(lat - lat0) * EARTH_RADIUS;
        return new double[]{east, north};
    }

    /** 两点间大圆距离（米），用于测试和显示。 */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** 地图朝向（弧度）→ 罗盘式描述，例如 "→ +x"。只用于界面显示。 */
    public static String arrow(double theta) {
        String[] arrows = {"→", "↗", "↑", "↖", "←", "↙", "↓", "↘"};
        int k = (int) Math.round(wrap(theta) / (Math.PI / 4));
        return arrows[((k % 8) + 8) % 8];
    }
}
