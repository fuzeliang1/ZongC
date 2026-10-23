package com.zongc.phone.core;

import java.io.IOException;
import java.io.Writer;

/**
 * 实验数据记录（CSV，逗号分隔，第一行是表头，MATLAB 用 readtable 直接读）。
 *
 * <p>一次记录生成两个文件，时间列 t_s 都从同一时刻起算（秒），可以直接对齐：</p>
 * <ul>
 *   <li>pose_*.csv —— 10 Hz 位姿与状态（{@link #POSE_HEADER}）；</li>
 *   <li>imu_*.csv  —— 原始传感器数据（{@link #IMU_HEADER}），用于在 MATLAB 里离线复现 PDR。</li>
 * </ul>
 * 写入失败（例如存储已满）不会抛异常打断传感器线程，而是记在 {@link #error()} 里。
 */
public final class CsvLog {
    public static final String POSE_HEADER = "t_s,x,y,theta,v,w,raw_x,raw_y,px,py,yaw_p,steps,distance,"
            + "step_len,cadence,walking,acc_sig,mag_yaw,tilt_deg,lat,lon,gnss_acc,gnss_used,"
            + "sats_used,bds_used,connected,rtt_ms";
    public static final String IMU_HEADER = "t_s,sensor,x,y,z";

    /** imu 文件 sensor 列的取值。 */
    public static final int ACC = 1;
    public static final int GYRO = 2;
    public static final int MAG = 3;
    public static final int GRAVITY = 4;

    private final Writer w;
    private final long t0;
    private final StringBuilder sb = new StringBuilder(256);
    private long rows;
    private String error;
    private boolean closed;

    /**
     * @param t0 记录开始时刻（ns），t_s = (t − t0)/1e9
     */
    public CsvLog(Writer w, String header, long t0) {
        this.w = w;
        this.t0 = t0;
        write(header + "\n");
    }

    /** 写一行位姿。sats/bds 为参与定位的卫星总数 / 北斗卫星数，未知时传 -1。 */
    public synchronized void pose(PdrState s, int satsUsed, int bdsUsed, boolean connected, double rttMs) {
        sb.setLength(0);
        Fmt.fixed(sb, (s.t - t0) * 1e-9, 3, "NaN").append(',');
        num(s.x, 3).num(s.y, 3).num(s.theta, 4).num(s.v, 3).num(s.w, 4);
        num(s.rawX, 3).num(s.rawY, 3).num(s.px, 3).num(s.py, 3).num(s.yawP, 4);
        sb.append(s.steps).append(',');
        num(s.distance, 3).num(s.stepLength, 3).num(s.cadence, 1);
        sb.append(s.walking ? 1 : 0).append(',');
        num(s.accSignal, 3).num(s.magYaw, 4).num(s.tiltDeg, 1);
        num(s.gnssLat, 7).num(s.gnssLon, 7).num(s.gnssAccuracy, 1);
        sb.append(s.gnssUsed ? 1 : 0).append(',');
        sb.append(satsUsed).append(',').append(bdsUsed).append(',');
        sb.append(connected ? 1 : 0).append(',');
        Fmt.fixed(sb, rttMs, 1, "NaN").append('\n');
        write(sb);
    }

    /** 写一行原始传感器数据。 */
    public synchronized void imu(long t, int sensor, double x, double y, double z) {
        sb.setLength(0);
        Fmt.fixed(sb, (t - t0) * 1e-9, 4, "NaN").append(',').append(sensor).append(',');
        num(x, 5).num(y, 5);
        Fmt.fixed(sb, z, 5, "NaN").append('\n');
        write(sb);
    }

    private CsvLog num(double v, int decimals) {
        Fmt.fixed(sb, v, decimals, "NaN").append(',');
        return this;
    }

    private void write(CharSequence text) {
        if (closed || error != null) return;
        try {
            w.append(text);
            rows++;
        } catch (IOException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    public synchronized void flush() {
        if (closed || error != null) return;
        try {
            w.flush();
        } catch (IOException e) {
            error = e.getMessage();
        }
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            w.close();
        } catch (IOException e) {
            if (error == null) error = e.getMessage();
        }
    }

    /** 已写入的行数（含表头）。 */
    public synchronized long rows() {
        return rows;
    }

    public synchronized String error() {
        return error;
    }
}
