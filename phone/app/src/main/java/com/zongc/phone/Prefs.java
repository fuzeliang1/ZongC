package com.zongc.phone;

import android.content.Context;
import android.content.SharedPreferences;

import com.zongc.phone.core.PdrConfig;

/** 保存在手机上的设置：服务端地址、手机编号、算法参数、上次的位置。 */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("minididi", Context.MODE_PRIVATE);
    }

    String host() {
        return sp.getString("host", "");
    }

    void setHost(String host) {
        sp.edit().putString("host", host).apply();
    }

    String phoneId() {
        String id = sp.getString("phoneId", "phone-1");
        return id == null || id.trim().isEmpty() ? "phone-1" : id.trim();
    }

    void setPhoneId(String id) {
        sp.edit().putString("phoneId", id).apply();
    }

    PdrConfig config() {
        return PdrConfig.fromJson(sp.getString("pdrConfig", null));
    }

    void setConfig(PdrConfig c) {
        sp.edit().putString("pdrConfig", c.toJson()).apply();
    }

    boolean hasLastPose() {
        return sp.contains("lastX");
    }

    /** {x, y, 地图旋转角}；没有保存过时返回 null。 */
    double[] lastPose() {
        if (!hasLastPose()) return null;
        return new double[]{sp.getFloat("lastX", 0), sp.getFloat("lastY", 0), sp.getFloat("lastRot", 0)};
    }

    void setLastPose(double x, double y, double rotation) {
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || Double.isInfinite(y)) return;
        sp.edit().putFloat("lastX", (float) x).putFloat("lastY", (float) y).putFloat("lastRot", (float) rotation).apply();
    }
}
