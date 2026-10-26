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
 * 访问数字世界的 HTTP 接口（端口 8080）：拉地图、拉快照、上传照片做图像识别。
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
     * 拍照识别：把一张 JPEG 照片上传给服务端的图像处理模块。服务端用这部手机所驾驶的车（source）在地图上的位姿
     * 把识别出的路障换算到地图格子上，写入全局地图，出租车随即绕行。
     *
     * @param source  手机车在数字世界里的编号（welcome 里的 agentId）
     * @param hfovDeg 相机水平视场角（度）
     * @param scale   比例：真实世界 1 米 = 地图上多少米（用驾驶比例 driveScale）
     */
    public VisionResult detectPhoto(String source, byte[] jpeg, double hfovDeg, double scale) throws IOException {
        String q = "/api/vision/detect?source=" + java.net.URLEncoder.encode(source, "UTF-8")
                + "&hfov=" + Fmt.show(hfovDeg, 1) + "&scale=" + Fmt.show(scale, 3);
        try {
            return VisionResult.fromJson(requestBytes("POST", q, "image/jpeg", jpeg));
        } catch (IllegalArgumentException e) {
            throw new IOException("服务端返回的识别结果无法解析");
        }
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
        return requestBytes(method, path, "application/json; charset=utf-8", body == null ? null : body.getBytes(UTF8));
    }

    /** 发送请求（请求体为任意字节，例如照片）并返回响应正文。 */
    String requestBytes(String method, String path, String contentType, byte[] bytes) throws IOException {
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
            if (bytes != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
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
