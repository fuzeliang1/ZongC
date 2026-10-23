package com.zongc.dw.map;

/**
 * 地图格子类型。字符与地图文件中的符号一一对应。
 * <ul>
 *   <li>车道格只能沿箭头方向驶入、驶出（靠右行驶）。</li>
 *   <li>路口格和开放区域格可以朝任意方向行驶。</li>
 * </ul>
 */
public enum CellType {
    BUILDING('#', null),
    FREE('.', null),
    CROSS('+', null),
    LANE_E('>', Dir.E),
    LANE_W('<', Dir.W),
    LANE_N('^', Dir.N),
    LANE_S('v', Dir.S);

    public final char symbol;
    /** 车道方向；非车道格为 null。 */
    public final Dir laneDir;

    CellType(char symbol, Dir laneDir) {
        this.symbol = symbol;
        this.laneDir = laneDir;
    }

    public boolean drivable() {
        return this != BUILDING;
    }

    public boolean isLane() {
        return laneDir != null;
    }

    public static CellType fromSymbol(char c) {
        for (CellType t : values()) {
            if (t.symbol == c) return t;
        }
        throw new IllegalArgumentException("未知的地图字符: '" + c + "'");
    }
}
