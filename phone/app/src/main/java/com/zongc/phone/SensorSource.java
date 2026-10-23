package com.zongc.phone;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.zongc.phone.core.CsvLog;
import com.zongc.phone.core.PdrEngine;

/**
 * 读取加速度计、陀螺仪、磁力计、重力传感器（约 50 Hz），送给 {@link PdrEngine}，
 * 需要时同时写原始数据日志。回调运行在单独的 "sensors" 线程，不占用界面线程。
 */
final class SensorSource implements SensorEventListener {
    /** 请求的采样周期（微秒）：20 ms = 50 Hz。 */
    private static final int PERIOD_US = 20000;

    private final SensorManager sm;
    private final PdrEngine engine;
    private HandlerThread thread;
    private volatile CsvLog imuLog;

    private boolean offsetKnown;
    private long offset;

    // 界面显示用：是否存在、累计事件数
    volatile boolean hasAccel;
    volatile boolean hasGyro;
    volatile boolean hasMag;
    volatile boolean hasGravity;
    volatile long accelCount;
    volatile long gyroCount;
    volatile long magCount;
    volatile int magAccuracy = -1;

    SensorSource(Context ctx, PdrEngine engine) {
        this.sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.engine = engine;
    }

    boolean running() {
        return thread != null;
    }

    void start() {
        if (thread != null || sm == null) return;
        thread = new HandlerThread("sensors");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        offsetKnown = false;
        hasAccel = register(Sensor.TYPE_ACCELEROMETER, h);
        hasGyro = register(Sensor.TYPE_GYROSCOPE, h);
        hasMag = register(Sensor.TYPE_MAGNETIC_FIELD, h);
        hasGravity = register(Sensor.TYPE_GRAVITY, h);
    }

    private boolean register(int type, Handler h) {
        Sensor s = sm.getDefaultSensor(type);
        return s != null && sm.registerListener(this, s, PERIOD_US, h);
    }

    void stop() {
        if (thread == null) return;
        sm.unregisterListener(this);
        thread.quitSafely();
        thread = null;
    }

    /** 设置原始数据日志（null 表示不记录）。 */
    void setImuLog(CsvLog log) {
        imuLog = log;
    }

    /**
     * 安卓 8.0 起传感器时间戳与 SystemClock.elapsedRealtimeNanos() 同一时基；
     * 个别机型不是，就按第一次收到时的差值校正，保证与界面、卫星定位的时间可比。
     */
    private long fixTime(long ts) {
        if (!offsetKnown) {
            long now = SystemClock.elapsedRealtimeNanos();
            offset = Math.abs(now - ts) > 1_000_000_000L ? now - ts : 0;
            offsetKnown = true;
        }
        return ts + offset;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long t = fixTime(e.timestamp);
        float[] v = e.values;
        if (v == null || v.length < 3) return;
        CsvLog log = imuLog;
        switch (e.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                engine.onAccel(t, v[0], v[1], v[2]);
                accelCount++;
                if (log != null) log.imu(t, CsvLog.ACC, v[0], v[1], v[2]);
                break;
            case Sensor.TYPE_GYROSCOPE:
                engine.onGyro(t, v[0], v[1], v[2]);
                gyroCount++;
                if (log != null) log.imu(t, CsvLog.GYRO, v[0], v[1], v[2]);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                engine.onMag(t, v[0], v[1], v[2]);
                magCount++;
                if (log != null) log.imu(t, CsvLog.MAG, v[0], v[1], v[2]);
                break;
            case Sensor.TYPE_GRAVITY:
                engine.onGravity(t, v[0], v[1], v[2]);
                if (log != null) log.imu(t, CsvLog.GRAVITY, v[0], v[1], v[2]);
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor != null && sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy;
    }
}
