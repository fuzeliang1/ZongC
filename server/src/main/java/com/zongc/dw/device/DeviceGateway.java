package com.zongc.dw.device;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.zongc.dw.config.Config;
import com.zongc.dw.sim.SimulationEngine;
import com.zongc.dw.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设备接入模块（网络部分）：
 * <ul>
 *   <li>TCP（默认 9000 端口）：每行一条 JSON，注册、状态上报、路线/授权下发都走这里；</li>
 *   <li>UDP（默认 9001 端口）：可选的高频状态上报通道，每个数据报一条 state 消息
 *       （设备须先通过 TCP 完成注册）。</li>
 * </ul>
 * 消息格式见 docs/protocol.md。
 */
public final class DeviceGateway {
    private final Config.Devices cfg;
    private final SimulationEngine engine;
    private final AtomicInteger seq = new AtomicInteger();
    private ServerSocket server;
    private DatagramSocket udp;

    public DeviceGateway(Config.Devices cfg, SimulationEngine engine) {
        this.cfg = cfg;
        this.engine = engine;
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(cfg.tcpPort));
        Thread acceptor = new Thread(this::acceptLoop, "tcp-accept");
        acceptor.setDaemon(true);
        acceptor.start();

        udp = new DatagramSocket(cfg.udpPort);
        Thread udpThread = new Thread(this::udpLoop, "udp");
        udpThread.setDaemon(true);
        udpThread.start();
    }

    public void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // 关闭时忽略
        }
        if (udp != null) udp.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                DeviceSession session = new DeviceSession(seq.incrementAndGet(), s, engine);
                Log.info("设备连接 " + session);
                session.start();
            } catch (IOException e) {
                if (!server.isClosed()) Log.error("接受设备连接失败", e);
            }
        }
    }

    private void udpLoop() {
        byte[] buf = new byte[65536];
        while (!udp.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                String text = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8).trim();
                String remote = p.getAddress().getHostAddress() + ":" + p.getPort();
                for (String line : text.split("\n")) {
                    if (line.isBlank()) continue;
                    try {
                        JsonElement e = JsonParser.parseString(line);
                        if (e.isJsonObject()) engine.submit(w -> w.onUdpMessage(e.getAsJsonObject(), remote));
                    } catch (RuntimeException ex) {
                        Log.debug("UDP 消息解析失败 " + remote + ": " + ex.getMessage());
                    }
                }
            } catch (IOException e) {
                if (!udp.isClosed()) Log.error("UDP 接收失败", e);
            }
        }
    }
}
