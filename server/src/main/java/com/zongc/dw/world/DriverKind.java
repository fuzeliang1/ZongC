package com.zongc.dw.world;

/** 数字车由谁驱动。INTERNAL 表示由数字世界自己仿真，其余表示由外部设备上报状态（数字孪生）。 */
public enum DriverKind {
    INTERNAL, MATLAB, GAZEBO, PHONE, GENERIC;

    /** 解析设备注册消息里的 kind 字段。 */
    public static DriverKind fromKind(String kind) {
        if (kind == null) return GENERIC;
        return switch (kind.trim().toLowerCase()) {
            case "matlab" -> MATLAB;
            case "gazebo", "ros", "ros2" -> GAZEBO;
            case "phone", "app", "android", "harmony", "ios" -> PHONE;
            default -> GENERIC;
        };
    }
}
