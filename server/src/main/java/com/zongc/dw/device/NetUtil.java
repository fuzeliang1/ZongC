package com.zongc.dw.device;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 网络小工具。 */
public final class NetUtil {
    private NetUtil() {}

    /** 本机所有可用的局域网 IPv4 地址（手机、另一台电脑上的 Matlab 用它来连接服务端）。 */
    public static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) out.add(addr.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
            // 无法枚举网卡时返回空列表
        }
        return out;
    }
}
