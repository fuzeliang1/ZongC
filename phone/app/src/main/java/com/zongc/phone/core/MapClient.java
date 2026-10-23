package com.zongc.phone.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * 访问数字世界的 HTTP 接口（端口 8080）：拉地图、拉快照、叫车。
 *
 * <p>全部是阻塞调用，必须在后台线程里用（安卓禁止在主线程联网）。</p>
 */
public final class MapClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String base;
    private final int timeoutMs;

    public MapClient(String host, int httpPort) {
        this(host, httpPort, 3000);
    }

    public MapClient(String host, int httpPort, int timeoutMs) {
        String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;   // IPv6
        this.base = "http://" + h + ":" + httpPort;
        this.timeoutMs = timeoutMs;
    }

    public String baseUrl() {
        return base;
    }

    public MapData fetchMap() throws IOException {
        try {
            return MapData.fromJson(request("GET", "/api/map", null));
        } catch (IllegalArgumentException e) {
            throw new IOException("地图数据格式不对：" + e.getMessage());
        }
    }

    public WorldSnapshot fetchSnapshot() throws IOException {
        try {
            return WorldSnapshot.fromJson(request("GET", "/api/snapshot", null));
        } catch (IllegalArgumentException e) {
            throw new IOException("快照数据格式不对：" + e.getMessage());
        }
    }

    /**
     * 叫车：在数字世界里下一个订单（上下车点必须是车道格）。
     *
     * @return 订单号，例如 "T012"
     */
    public String hail(int fromX, int fromY, int toX, int toY) throws IOException {
        String body = new Json.Obj()
                .raw("from", "[" + fromX + "," + fromY + "]")
                .raw("to", "[" + toX + "," + toY + "]")
                .toString();
        Map<String, Object> o = parse(request("POST", "/api/tasks", body));
        String id = Json.str(o, "taskId");
        if (id == null) throw new IOException("服务端没有返回订单号");
        return id;
    }

    private static Map<String, Object> parse(String json) throws IOException {
        try {
            Map<String, Object> o = Json.parseObject(json);
            if (o == null) throw new IOException("服务端返回的不是 JSON 对象");
            return o;
        } catch (IllegalArgumentException e) {
            throw new IOException("服务端返回的 JSON 无法解析");
        }
    }

    /** 发送请求并返回响应正文；HTTP 状态码 ≥ 400 时抛出带服务端错误信息的 IOException。 */
    String request(String method, String path, String body) throws IOException {
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("地址格式不对：" + base + path);
        }
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.getBytes(UTF8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) {
                String msg = null;
                try {
                    msg = Json.str(Json.parseObject(text), "error");
                } catch (IllegalArgumentException ignored) {
                    // 不是 JSON：用状态码
                }
                throw new IOException(msg != null ? msg : "HTTP " + code + " " + path);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }
}
