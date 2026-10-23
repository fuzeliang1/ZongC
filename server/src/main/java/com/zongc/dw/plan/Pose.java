package com.zongc.dw.plan;

/** 平面位姿：位置（米）和航向角（弧度，从 x 轴逆时针）。航向未定义时为 NaN。 */
public record Pose(double x, double y, double theta) {}
