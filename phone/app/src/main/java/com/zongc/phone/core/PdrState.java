package com.zongc.phone.core;

/**
 * {@link PdrEngine} 某一时刻的状态快照（每次调用 {@link PdrEngine#state(long)} 新建一个，之后不再修改）。
 *
 * <p>长度单位：地图坐标为"地图米"，其余为真实米。角度为弧度，逆时针从地图 +x 量起。</p>
 */
public final class PdrState {
    /** 快照时间（ns，与传感器时间戳同一时基）。 */
    public long t;

    // ---- 地图坐标（上报给数字世界的位姿，已平滑）
    public double x;
    public double y;
    public double theta;
    /** 地图坐标系下的速度（地图米/秒）。 */
    public double v;
    /** 竖直角速度（rad/s）。 */
    public double w;
    /** 未平滑的地图坐标。 */
    public double rawX;
    public double rawY;

    // ---- 内部坐标系 P
    public double px;
    public double py;
    public double yawP;

    // ---- 步伐
    public int steps;
    /** 累计步行距离（真实米）。 */
    public double distance;
    /** 最近一步的步长（真实米）。 */
    public double stepLength;
    /** 步频（步/分钟）。 */
    public double cadence;
    public boolean walking;
    /** 滤波后的加速度信号（m/s²），画波形用。 */
    public double accSignal;

    // ---- 模式与标定
    public boolean simWalk;
    public boolean outdoor;
    public boolean calibrating;
    public int calibSteps;
    public double calibDistance;
    public double weinbergK;
    public double scale;
    /** 地图坐标系相对内部坐标系的旋转角 φ。 */
    public double rotation;

    // ---- 传感器
    public boolean hasGyro;
    public boolean hasMag;
    /** 磁力计给出的地理航向；没有或不可信时为 NaN。 */
    public double magYaw;
    public double magNorm;
    public double tiltDeg;
    public double gyroBiasUp;

    // ---- 卫星定位
    public double gnssLat = Double.NaN;
    public double gnssLon = Double.NaN;
    public double gnssAccuracy = Double.NaN;
    /** 距最近一次定位的时间（s）；从未定位过为 NaN。 */
    public double gnssAge = Double.NaN;
    /** 最近一次定位是否参与了位置融合。 */
    public boolean gnssUsed;
    /** 位置融合的标准差估计（真实米），仅室外模式有意义。 */
    public double posStd = Double.NaN;
}
