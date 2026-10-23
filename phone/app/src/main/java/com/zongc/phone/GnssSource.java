package com.zongc.phone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.zongc.phone.core.PdrEngine;

/**
 * 卫星定位：直接用系统的 GPS_PROVIDER（同时接收 GPS、北斗、GLONASS、Galileo 等）。
 *
 * <p>不用 Google 的融合定位（国内手机大多没有 Google 服务）。GnssStatus 回调给出每颗卫星的
 * 星座类型和是否参与定位，可以统计北斗卫星数。定位结果送给 {@link PdrEngine}，
 * 只在室外模式下参与位置融合。室内基本收不到卫星，这是正常的。</p>
 */
final class GnssSource {
    private final Context ctx;
    private final LocationManager lm;
    private final PdrEngine engine;
    private boolean running;

    // 界面显示用
    volatile int visible;
    volatile int used;
    volatile int beidouVisible;
    volatile int beidouUsed;
    volatile int gpsUsed;
    volatile int glonassUsed;
    volatile int galileoUsed;
    volatile long fixes;
    volatile float lastAccuracy = Float.NaN;
    volatile String error;

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            fixes++;
            float acc = loc.hasAccuracy() ? loc.getAccuracy() : Float.NaN;
            lastAccuracy = acc;
            engine.onLocation(loc.getElapsedRealtimeNanos(), loc.getLatitude(), loc.getLongitude(), acc);
        }

        // 下面三个方法在安卓 11 以前是抽象方法，必须实现，否则旧手机上会崩溃
        @SuppressWarnings("deprecation")
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            error = null;
        }

        @Override
        public void onProviderDisabled(String provider) {
            error = "系统定位已关闭：请在下拉菜单里打开“位置信息”";
        }
    };

    private final GnssStatus.Callback statusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            int n = status.getSatelliteCount();
            int vis = 0, use = 0, bdsV = 0, bdsU = 0, gps = 0, glo = 0, gal = 0;
            for (int i = 0; i < n; i++) {
                int type = status.getConstellationType(i);
                boolean inFix = status.usedInFix(i);
                vis++;
                if (inFix) use++;
                if (type == GnssStatus.CONSTELLATION_BEIDOU) {
                    bdsV++;
                    if (inFix) bdsU++;
                } else if (inFix && type == GnssStatus.CONSTELLATION_GPS) {
                    gps++;
                } else if (inFix && type == GnssStatus.CONSTELLATION_GLONASS) {
                    glo++;
                } else if (inFix && type == GnssStatus.CONSTELLATION_GALILEO) {
                    gal++;
                }
            }
            visible = vis;
            used = use;
            beidouVisible = bdsV;
            beidouUsed = bdsU;
            gpsUsed = gps;
            glonassUsed = glo;
            galileoUsed = gal;
        }

        @Override
        public void onStopped() {
            visible = 0;
            used = 0;
            beidouVisible = 0;
            beidouUsed = 0;
        }
    };

    GnssSource(Context ctx, PdrEngine engine) {
        this.ctx = ctx;
        this.lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        this.engine = engine;
    }

    boolean running() {
        return running;
    }

    boolean permitted() {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** 开始接收卫星定位（需要已获得精确位置权限）。 */
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    void start() {
        if (running) return;
        if (lm == null) {
            error = "这台手机没有定位服务";
            return;
        }
        if (!permitted()) {
            error = "没有“精确位置”权限，无法使用卫星定位";
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper());
            lm.registerGnssStatusCallback(statusCallback, new Handler(Looper.getMainLooper()));
            running = true;
            error = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? null
                    : "系统定位已关闭：请在下拉菜单里打开“位置信息”";
        } catch (SecurityException e) {
            error = "没有定位权限：" + e.getMessage();
        } catch (IllegalArgumentException e) {
            error = "这台手机没有卫星定位模块";
        }
    }

    void stop() {
        if (!running) return;
        lm.removeUpdates(listener);
        lm.unregisterGnssStatusCallback(statusCallback);
        running = false;
        visible = 0;
        used = 0;
        beidouVisible = 0;
        beidouUsed = 0;
    }
}
