package com.zongc.phone;

import android.content.Context;
import android.content.SharedPreferences;

import com.zongc.phone.core.PdrConfig;

/** 保存在手机上的设置：服务端地址、是否自动连接、要驾驶的车号、算法参数。 */
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

    /** 打开 App 时自动连接上次的服务端（默认开启：考核时打开 App 即可，不用再点"连接"）。 */
    boolean autoConnect() {
        return sp.getBoolean("autoConnect", true);
    }

    void setAutoConnect(boolean on) {
        sp.edit().putBoolean("autoConnect", on).apply();
    }

    /**
     * 希望驾驶的数字车编号。默认 car-6：Matlab 车队用 car-1 ~ car-5，手机接着用下一辆。
     * 这辆车正被别的设备驾驶时，服务端会分配另一辆空闲的车。
     */
    String carId() {
        String id = sp.getString("carId", "car-6");
        return id == null || id.trim().isEmpty() ? "car-6" : id.trim();
    }

    void setCarId(String id) {
        sp.edit().putString("carId", id).apply();
    }

    PdrConfig config() {
        return PdrConfig.fromJson(sp.getString("pdrConfig", null));
    }

    void setConfig(PdrConfig c) {
        sp.edit().putString("pdrConfig", c.toJson()).apply();
    }
}
