package com.zongc.dw.device;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zongc.dw.sim.SimulationEngine;
import com.zongc.dw.util.Log;
import com.zongc.dw.world.DeviceLink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个设备的 TCP 连接。协议：UTF-8 文本，每行一条 JSON 消息（以 '\n' 结尾）。
 * 读线程把消息交给仿真线程处理；写线程从队列取消息发送，仿真线程发送时不会被网络阻塞。
 */
final class DeviceSession implements DeviceLink {
    private static final int MAX_QUEUE = 2000;
    private static final int MAX_LINE = 1 << 20;

    private final String id;
    private final Socket socket;
    private final String remote;
    private final SimulationEngine engine;
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    DeviceSession(int seq, Socket socket, SimulationEngine engine) {
        this.id = "tcp#" + seq;
        this.socket = socket;
        this.remote = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        this.engine = engine;
    }

    void start() {
        Thread reader = new Thread(this::readLoop, id + "-r");
        Thread writer = new Thread(this::writeLoop, id + "-w");
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (open.get() && (line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.length() > MAX_LINE) {
                    sendError("消息过长");
                    continue;
                }
                JsonObject msg;
                try {
                    JsonElement e = JsonParser.parseString(line);
                    if (!e.isJsonObject()) {
                        sendError("每行必须是一个 JSON 对象");
                        continue;
                    }
                    msg = e.getAsJsonObject();
                } catch (RuntimeException ex) {
                    sendError("JSON 解析失败: " + ex.getMessage());
                    continue;
                }
                engine.submit(w -> w.onDeviceMessage(this, msg));
            }
        } catch (IOException e) {
            if (open.get()) Log.debug(id + " 读取结束: " + e.getMessage());
        } finally {
            close("connection closed");
            engine.submit(w -> w.onDeviceClosed(this));
        }
    }

    private void writeLoop() {
        try {
            OutputStream out = socket.getOutputStream();
            while (open.get()) {
                String msg = outbox.poll(500, TimeUnit.MILLISECONDS);
                if (msg == null) continue;
                StringBuilder batch = new StringBuilder(msg).append('\n');
                String more;
                while ((more = outbox.poll()) != null) batch.append(more).append('\n');
                out.write(batch.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            close("write failed");
        }
    }

    private void sendError(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("message", message);
        send(o.toString());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String remote() {
        return remote;
    }

    @Override
    public void send(String json) {
        if (!open.get()) return;
        if (outbox.size() > MAX_QUEUE) {
            close("发送队列溢出（设备读取过慢）");
            return;
        }
        outbox.add(json);
    }

    @Override
    public void close(String reason) {
        if (open.compareAndSet(true, false)) {
            Log.info(id + " (" + remote + ") 关闭: " + reason);
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已关闭
            }
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public String toString() {
        return id + "@" + remote;
    }
}
