package com.zongc.phone.core;

/**
 * 手机内部坐标系 P（真实米）→ 数字世界地图坐标系 M（地图米）的相似变换：
 * <pre>
 *   p_M = o_M + s · R(φ) · (p_P − o_P)
 *   θ_M = θ_P + φ
 * </pre>
 * <ul>
 *   <li>o_P / o_M：锚点（同一个物理位置在两个坐标系里的坐标）；</li>
 *   <li>s：比例尺（真实 1 米 = 地图 s 米）；</li>
 *   <li>φ：两个坐标系的夹角。</li>
 * </ul>
 * 修改任何参数前先把"当前位置"设为新锚点，地图上的位置就不会跳变。
 */
public final class MapFrame {
    private double oPx, oPy;
    private double oMx, oMy;
    private double phi;
    private double scale = 1;

    /** 让 P 中的点 (px, py) 对应地图上的 (mx, my)。 */
    public void anchor(double px, double py, double mx, double my) {
        oPx = px;
        oPy = py;
        oMx = mx;
        oMy = my;
    }

    public double[] toMap(double px, double py) {
        double dx = px - oPx, dy = py - oPy;
        double c = Math.cos(phi), s = Math.sin(phi);
        return new double[]{oMx + scale * (c * dx - s * dy), oMy + scale * (s * dx + c * dy)};
    }

    /** 地图坐标 → P（toMap 的逆变换）。 */
    public double[] toInternal(double mx, double my) {
        double dx = (mx - oMx) / scale, dy = (my - oMy) / scale;
        double c = Math.cos(phi), s = Math.sin(phi);
        return new double[]{oPx + c * dx + s * dy, oPy - s * dx + c * dy};
    }

    public double toMapHeading(double thetaP) {
        return Geo.wrap(thetaP + phi);
    }

    /** 保持 P 中的点 (px, py) 在地图上的位置不变，改为新的旋转角。 */
    public void setRotationKeeping(double px, double py, double newPhi) {
        double[] m = toMap(px, py);
        phi = Geo.wrap(newPhi);
        anchor(px, py, m[0], m[1]);
    }

    /** 保持 P 中的点 (px, py) 在地图上的位置不变，改为新的比例尺。 */
    public void setScaleKeeping(double px, double py, double newScale) {
        if (!(newScale > 0)) throw new IllegalArgumentException("比例尺必须大于 0");
        double[] m = toMap(px, py);
        scale = newScale;
        anchor(px, py, m[0], m[1]);
    }

    public double rotation() {
        return phi;
    }

    public double scale() {
        return scale;
    }
}
