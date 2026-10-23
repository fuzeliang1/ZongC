package com.zongc.dw.world;

/** 数字世界看到的一个设备连接（由 device 包实现），与具体网络实现解耦。 */
public interface DeviceLink {
    /** 连接编号，例如 "tcp#3"。 */
    String id();

    /** 对端地址，例如 "192.168.1.20:52144"。 */
    String remote();

    /** 发送一行 JSON（非阻塞，内部排队）。 */
    void send(String json);

    /** 关闭连接。 */
    void close(String reason);

    boolean isOpen();
}
