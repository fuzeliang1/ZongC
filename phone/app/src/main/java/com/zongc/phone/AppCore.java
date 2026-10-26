package com.zongc.phone;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import com.zongc.phone.core.DwConnection;
import com.zongc.phone.core.Endpoint;
import com.zongc.phone.core.MapClient;
import com.zongc.phone.core.VisionResult;
import com.zongc.phone.core.MapData;
import com.zongc.phone.core.PdrConfig;
import com.zongc.phone.core.PdrEngine;
import com.zongc.phone.core.PdrState;
import com.zongc.phone.core.RouteFollower;
import com.zongc.phone.core.WorldPoller;
import com.zongc.phone.core.WorldSnapshot;

import java.io.IOException;
import java.util.Map;

/**
 * 整个 App 只有一份的"内核"：位姿估计、传感器、卫星定位、手机车、与数字世界的连接、数据记录。
 *
 * <p>手机 = 数字世界里的一辆模拟车：连接后服务端绑定一辆数字车（默认 car-6），下发路线和移动授权；
 * {@link RouteFollower} 以走路速度为油门沿路线推进，每 0.1 s 把车的位姿上报给服务端。</p>
 *
 * <p>放在 Activity 之外，这样界面因为深色模式切换、分屏等原因重建时，连接和推算都不中断。
 * 带 "UI 线程" 注释的字段只在界面线程读写。</p>
 */
final class AppCore {
    private static AppCore instance;

    static synchronized AppCore get(Context ctx) {
        if (instance == null) instance = new AppCore(ctx.getApplicationContext());
        return instance;
    }

    final Context app;
    final Prefs prefs;
    final PdrEngine engine;
    final RouteFollower car = new RouteFollower();
    final SensorSource sensors;
    final GnssSource gnss;

    private DwConnection conn;
    private WorldPoller poller;
    private Endpoint endpoint;
    private LogFiles.Recording recording;
    private volatile MapData lastMap;
    /** 驾驶比例：人走 1 m，车沿路线开多少 m（连接线程读，设置界面写）。 */
    private volatile double driveScale;

    // ---- 轨迹（UI 线程）：车在地图上的轨迹、手机自己推算的步行轨迹（没连接时显示）
    final Trail carTrail = new Trail();
    final Trail walkTrail = new Trail();
    private String trailCar;

    private AppCore(Context app) {
        this.app = app;
        prefs = new Prefs(app);
        PdrConfig c = prefs.config();
        engine = new PdrEngine(c);
        driveScale = c.driveScale;
        sensors = new SensorSource(app, engine);
        gnss = new GnssSource(app, engine);
    }

    static long now() {
        return SystemClock.elapsedRealtimeNanos();
    }

    // ================================================================ 连接

    synchronized void connect(Endpoint ep) {
        disconnect();
        endpoint = ep;
        poller = new WorldPoller(new MapClient(ep.host, ep.httpPort), 300);
        poller.start();
        conn = new DwConnection(ep.host, ep.tcpPort, prefs.carId(), Build.MANUFACTURER + " " + Build.MODEL,
                new DwConnection.PoseSource() {
                    @Override
                    public double[] pose() {
                        return drive();
                    }
                }, new DwConnection.Listener() {
                    @Override
                    public void onMessage(Map<String, Object> m) {
                        car.onMessage(m);
                    }

                    @Override
                    public void onDisconnected() {
                        car.reset();       // 服务端已由内部仿真接管这辆车；重连后按新的 welcome 重新开始
                    }
                }, 10);
        conn.start();
    }

    synchronized void disconnect() {
        if (conn != null) conn.stop();
        conn = null;
        if (poller != null) poller.stop();
        poller = null;
        car.reset();
    }

    /** 正在连接或已连接（用户点过"连接"且没点"断开"）。 */
    synchronized boolean active() {
        return conn != null;
    }

    synchronized Endpoint endpoint() {
        return endpoint;
    }

    synchronized DwConnection.Status connStatus() {
        return conn == null ? null : conn.status();
    }

    synchronized void sendEvent(String text) {
        if (conn != null) conn.sendEvent(text);
    }

    /** 地图：连接后从服务端获取；断开后继续显示最后一次的地图。 */
    MapData map() {
        WorldPoller p = currentPoller();
        MapData m = p == null ? null : p.map();
        if (m != null) lastMap = m;
        return lastMap;
    }

    WorldSnapshot snapshot() {
        WorldPoller p = currentPoller();
        return p == null ? null : p.snapshot();
    }

    String pollError() {
        WorldPoller p = currentPoller();
        return p == null ? null : p.error();
    }

    /** 拍照识别时假设的相机水平视场角（度）：手机后置主摄一般在 60°~70° 之间。 */
    static final double PHOTO_HFOV_DEG = 63;

    /**
     * 拍照识别（阻塞，后台线程调用）：把照片交给服务端的图像处理模块。服务端按手机车在数字世界里的位姿
     * 把识别出的路障换算到地图格子上并写入全局地图。
     */
    VisionResult detectPhoto(byte[] jpeg) throws IOException {
        WorldPoller p = currentPoller();
        DwConnection.Status cs = connStatus();
        if (p == null || cs == null || cs.agentId == null) throw new IOException("未连接服务端");
        return p.detectPhoto(cs.agentId, jpeg, PHOTO_HFOV_DEG, driveScale);
    }

    private synchronized WorldPoller currentPoller() {
        return poller;
    }

    /**
     * 开一拍车：期望车速 = 走路速度 × 驾驶比例，沿路线推进后返回要上报的位姿 {x, y, θ, v, ω}。
     * 由连接线程每 0.1 s 调用；还没绑定数字车时返回 null（不上报）。
     */
    double[] drive() {
        long t = now();
        PdrState s = engine.state(t);
        return car.step(t, s.v * driveScale);
    }

    // ================================================================ 参数

    void applyConfig(PdrConfig c) {
        engine.applyConfig(c);
        PdrConfig applied = engine.config();
        driveScale = applied.driveScale;
        prefs.setConfig(applied);
    }

    /** 记录轨迹点（UI 线程，每 0.1 s 调用）。换了一辆车时清掉车的旧轨迹。 */
    void track(PdrState s, RouteFollower.Status c) {
        walkTrail.add(s.px, s.py);
        String id = c.ready ? c.agentId : null;
        if (id == null) return;
        if (!id.equals(trailCar)) {
            carTrail.clear();
            trailCar = id;
        }
        carTrail.add(c.x, c.y);
    }

    /** 最近 900 个轨迹点的环形缓冲区（移动超过 10 cm 才记）。 */
    static final class Trail {
        static final int SIZE = 900;
        final float[] x = new float[SIZE];
        final float[] y = new float[SIZE];
        int count;
        int head;

        void add(double px, double py) {
            if (count > 0) {
                int last = (head - 1 + SIZE) % SIZE;
                if (Math.hypot(px - x[last], py - y[last]) < 0.1) return;
            }
            x[head] = (float) px;
            y[head] = (float) py;
            head = (head + 1) % SIZE;
            if (count < SIZE) count++;
        }

        void clear() {
            count = 0;
            head = 0;
        }
    }

    // ================================================================ 数据记录

    synchronized boolean recording() {
        return recording != null;
    }

    /** 开始记录，返回保存位置。 */
    synchronized String startRecording() throws IOException {
        if (recording != null) return recording.where;
        recording = LogFiles.open(app, now());
        sensors.setImuLog(recording.imu);
        return recording.where;
    }

    /** 停止记录；没有在记录时返回 null。 */
    synchronized LogFiles.Recording stopRecording() {
        if (recording == null) return null;
        sensors.setImuLog(null);
        LogFiles.Recording r = recording;
        recording = null;
        r.close();
        return r;
    }

    /** 写一行日志（UI 线程每 0.1 s 调用）。 */
    void logPose(PdrState s, RouteFollower.Status c, DwConnection.Status cs) {
        LogFiles.Recording r;
        synchronized (this) {
            r = recording;
        }
        if (r == null) return;
        boolean gnssOn = gnss.running();
        r.pose.pose(s, c, gnssOn ? gnss.used : -1, gnssOn ? gnss.beidouUsed : -1,
                cs != null && cs.state == DwConnection.State.CONNECTED, cs == null ? Double.NaN : cs.rttMs);
    }

    /** 把缓冲区写进文件（界面每 2 s 调用一次，防止 App 被系统杀掉时丢数据）。 */
    synchronized void flushRecording() {
        if (recording == null) return;
        recording.pose.flush();
        recording.imu.flush();
    }

    synchronized long recordedRows() {
        return recording == null ? 0 : recording.pose.rows() + recording.imu.rows();
    }

    synchronized String recordingError() {
        return recording == null ? null : recording.error();
    }

    // ================================================================ 退出

    synchronized void shutdown() {
        disconnect();
        stopRecording();
        sensors.stop();
        gnss.stop();
    }
}
