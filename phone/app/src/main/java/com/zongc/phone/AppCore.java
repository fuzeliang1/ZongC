package com.zongc.phone;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import com.zongc.phone.core.DwConnection;
import com.zongc.phone.core.Endpoint;
import com.zongc.phone.core.MapClient;
import com.zongc.phone.core.MapData;
import com.zongc.phone.core.PdrConfig;
import com.zongc.phone.core.PdrEngine;
import com.zongc.phone.core.PdrState;
import com.zongc.phone.core.WorldPoller;
import com.zongc.phone.core.WorldSnapshot;

import java.io.IOException;

/**
 * 整个 App 只有一份的"内核"：位姿估计、传感器、卫星定位、与数字世界的连接、数据记录。
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
    final SensorSource sensors;
    final GnssSource gnss;

    private DwConnection conn;
    private WorldPoller poller;
    private Endpoint endpoint;
    private LogFiles.Recording recording;
    private volatile MapData lastMap;
    private volatile boolean placed;

    // ---- 我叫的车（UI 线程）
    String myTaskId;
    String myTaskInfo;

    // ---- 我走过的轨迹，地图坐标（UI 线程）
    static final int TRAIL = 900;
    final float[] trailX = new float[TRAIL];
    final float[] trailY = new float[TRAIL];
    int trailCount;
    int trailHead;

    private AppCore(Context app) {
        this.app = app;
        prefs = new Prefs(app);
        engine = new PdrEngine(prefs.config());
        double[] last = prefs.lastPose();
        if (last != null) {
            engine.restore(last[0], last[1], last[2]);
            placed = true;
        }
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
        conn = new DwConnection(ep.host, ep.tcpPort, prefs.phoneId(), Build.MANUFACTURER + " " + Build.MODEL,
                new DwConnection.PoseSource() {
                    @Override
                    public double[] pose() {
                        return reportPose();
                    }
                }, 10);
        conn.start();
    }

    synchronized void disconnect() {
        if (conn != null) conn.stop();
        conn = null;
        if (poller != null) poller.stop();
        poller = null;
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
        if (m != null) {
            lastMap = m;
            if (!placed) {
                // 第一次拿到地图且没有保存过位置：先放在地图中央，之后由用户"设位置"
                engine.setPosition(m.worldWidth() / 2, m.worldHeight() / 2);
                placed = true;
            }
        }
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

    /** 叫车（阻塞，后台线程调用）。 */
    String hail(int fx, int fy, int tx, int ty) throws IOException {
        WorldPoller p = currentPoller();
        if (p == null) throw new IOException("未连接服务端");
        return p.hail(fx, fy, tx, ty);
    }

    private synchronized WorldPoller currentPoller() {
        return poller;
    }

    /** 上报给数字世界的位姿（地图坐标，限制在地图范围内）。由连接线程每 0.1 s 调用。 */
    double[] reportPose() {
        PdrState s = engine.state(now());
        double x = s.x, y = s.y;
        MapData m = lastMap;
        if (m != null) {
            double[] c = m.clamp(x, y);
            x = c[0];
            y = c[1];
        }
        return new double[]{x, y, s.theta, s.v, s.w};
    }

    // ================================================================ 位置与参数

    void setPosition(double x, double y) {
        engine.setPosition(x, y);
        placed = true;
        clearTrail();
        savePose();
    }

    void applyConfig(PdrConfig c) {
        engine.applyConfig(c);
        prefs.setConfig(engine.config());
    }

    void savePose() {
        PdrState s = engine.state(now());
        prefs.setLastPose(s.rawX, s.rawY, s.rotation);
    }

    /** 记录一个轨迹点（UI 线程，每 0.1 s 调用；移动超过 10 cm 才记）。 */
    void addTrail(double x, double y) {
        if (trailCount > 0) {
            int last = (trailHead - 1 + TRAIL) % TRAIL;
            if (Math.hypot(x - trailX[last], y - trailY[last]) < 0.1) return;
        }
        trailX[trailHead] = (float) x;
        trailY[trailHead] = (float) y;
        trailHead = (trailHead + 1) % TRAIL;
        if (trailCount < TRAIL) trailCount++;
    }

    void clearTrail() {
        trailCount = 0;
        trailHead = 0;
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

    /** 写一行位姿日志（UI 线程每 0.1 s 调用）。 */
    void logPose(PdrState s, DwConnection.Status cs) {
        LogFiles.Recording r;
        synchronized (this) {
            r = recording;
        }
        if (r == null) return;
        boolean gnssOn = gnss.running();
        r.pose.pose(s, gnssOn ? gnss.used : -1, gnssOn ? gnss.beidouUsed : -1,
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
        savePose();
    }
}
