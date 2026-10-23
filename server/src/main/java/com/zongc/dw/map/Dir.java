package com.zongc.dw.map;

/**
 * 栅格上的四个行驶方向。坐标约定：x 向东为正，y 向北为正，航向角从 x 轴逆时针计（弧度）。
 */
public enum Dir {
    E(1, 0), N(0, 1), W(-1, 0), S(0, -1);

    public final int dx;
    public final int dy;

    Dir(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public Dir opposite() {
        return switch (this) {
            case E -> W;
            case W -> E;
            case N -> S;
            case S -> N;
        };
    }

    /** 左侧方向（逆时针转 90°）。靠右行驶时，对向车道在左侧。 */
    public Dir left() {
        return switch (this) {
            case E -> N;
            case N -> W;
            case W -> S;
            case S -> E;
        };
    }

    /** 该方向对应的航向角（弧度）。 */
    public double angle() {
        return Math.atan2(dy, dx);
    }

    /** 从 this 转向 out 是否为左转（逆时针）。 */
    public boolean isLeftTurnTo(Dir out) {
        return dx * out.dy - dy * out.dx > 0;
    }

    /** 由相邻两格的坐标差得到方向；不相邻时返回 null。 */
    public static Dir fromDelta(int dx, int dy) {
        for (Dir d : values()) {
            if (d.dx == dx && d.dy == dy) return d;
        }
        return null;
    }

    /** 把任意航向角吸附到最接近的方向。 */
    public static Dir fromAngle(double theta) {
        double c = Math.cos(theta), s = Math.sin(theta);
        if (Math.abs(c) >= Math.abs(s)) return c >= 0 ? E : W;
        return s >= 0 ? N : S;
    }
}
