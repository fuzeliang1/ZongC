package com.zongc.dw.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zongc.dw.config.Config;
import com.zongc.dw.device.NetUtil;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.sim.SimulationEngine;
import com.zongc.dw.sim.SnapshotHub;
import com.zongc.dw.util.Log;
import com.zongc.dw.vision.VisionService;
import com.zongc.dw.world.Agent;
import com.zongc.dw.world.DeviceBridge;
import com.zongc.dw.world.Scenario;
import com.zongc.dw.world.Task;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP 接口（可视化与人机交互模块的服务端部分），基于 JDK 自带的 HttpServer：
 * <ul>
 *   <li>REST：地图、快照、配置、订单、路障、事件、场景脚本、图像识别（见 docs/protocol.md 第 3 节）；</li>
 *   <li>SSE（Server-Sent Events）：/api/stream 以约 10 Hz 推送世界快照，浏览器用 EventSource 接收；</li>
 *   <li>静态文件：web/ 目录下的前端页面。</li>
 * </ul>
 */
public final class HttpApi {
    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("glb", "model/gltf-binary"),
            Map.entry("gltf", "model/gltf+json"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    private final Config cfg;
    private final SimulationEngine engine;
    private final Path webRoot;
    private final Gson gson = new Gson();
    private final String mapJson;
    private final VisionService vision;
    private HttpServer server;

    public HttpApi(Config cfg, SimulationEngine engine, Path webRoot) {
        this.cfg = cfg;
        this.engine = engine;
        this.webRoot = webRoot.toAbsolutePath().normalize();
        this.mapJson = buildMapJson(engine.map());
        this.vision = new VisionService(engine.map());
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(cfg.http.port), 128);
        server.createContext("/", this::handle);
        AtomicInteger n = new AtomicInteger();
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ------------------------------------------------------------------ 路由

    private void handle(HttpExchange ex) {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        boolean streaming = false;
        try {
            Headers h = ex.getResponseHeaders();
            h.set("Access-Control-Allow-Origin", "*");
            h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            h.set("Access-Control-Allow-Headers", "Content-Type");
            if (method.equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!path.startsWith("/api/")) {
                serveStatic(ex, path);
                return;
            }
            switch (method + " " + path) {
                case "GET /api/map" -> sendJson(ex, 200, mapJson);
                case "GET /api/snapshot" -> sendJson(ex, 200, engine.hub().latest());
                case "GET /api/stream" -> {
                    streaming = true;
                    stream(ex);
                }
                case "GET /api/config" -> sendJson(ex, 200, configJson().toString());
                case "POST /api/config" -> updateConfig(ex);
                case "POST /api/sim/pause" -> {
                    engine.setPaused(true);
                    sendJson(ex, 200, configJson().toString());
                }
                case "POST /api/sim/resume" -> {
                    engine.setPaused(false);
                    sendJson(ex, 200, configJson().toString());
                }
                case "POST /api/sim/reset" -> {
                    JsonObject body = readBody(ex);
                    Long seed = body.has("seed") ? body.get("seed").getAsLong() : null;
                    engine.reset(seed);
                    sendJson(ex, 200, ok("已重置"));
                }
                case "POST /api/tasks" -> createTask(ex);
                case "POST /api/roadblocks" -> roadblock(ex);
                case "GET /api/events" -> sendJson(ex, 200, gson.toJson(engine.events().recent(queryInt(ex, "n", 100))));
                case "GET /api/info" -> sendJson(ex, 200, infoJson().toString());
                case "GET /api/scenarios" -> sendJson(ex, 200, scenariosJson().toString());
                case "POST /api/scenario" -> switchScenario(ex);
                case "POST /api/vision/detect" -> visionDetect(ex);
                case "GET /api/vision/last" -> {
                    VisionService.Report r = vision.last();
                    if (r == null) sendJson(ex, 404, error("还没有识别过图片"));
                    else sendJson(ex, 200, gson.toJson(r));
                }
                case "GET /api/vision/sample.jpg" -> sendBytes(ex, "image/jpeg", sampleImage());
                case "GET /api/vision/last.jpg" -> {
                    byte[] img = vision.lastImage();
                    if (img == null) sendJson(ex, 404, error("还没有识别过图片"));
                    else sendBytes(ex, "image/jpeg", img);
                }
                default -> sendJson(ex, 404, error("没有这个接口: " + method + " " + path));
            }
        } catch (BadRequest e) {
            sendJson(ex, 400, error(e.getMessage()));
        } catch (Throwable t) {
            Log.error("HTTP " + method + " " + path + " 处理失败", t);
            sendJson(ex, 500, error("服务器内部错误: " + t.getMessage()));
        } finally {
            if (!streaming) ex.close();
        }
    }

    // ------------------------------------------------------------------ SSE

    private void stream(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-cache");
        h.set("X-Accel-Buffering", "no");
        SnapshotHub hub = engine.hub();
        try (OutputStream out = ex.getResponseBody()) {
            ex.sendResponseHeaders(200, 0);
            out.write("retry: 1000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            long known = 0;
            while (true) {
                Object[] next = hub.awaitNewer(known, 15000);
                if (next == null) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    known = (Long) next[0];
                    out.write(("data: " + next[1] + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // 浏览器关闭页面或网络断开，正常结束
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ 处理函数

    private void updateConfig(HttpExchange ex) throws Exception {
        JsonObject b = readBody(ex);
        if (b.has("timeScale")) engine.setTimeScale(b.get("timeScale").getAsDouble());
        if (b.has("paused")) engine.setPaused(b.get("paused").getAsBoolean());
        if (b.has("strategy")) {
            String s = b.get("strategy").getAsString();
            if (!s.equals("GREEDY") && !s.equals("HUNGARIAN")) throw new BadRequest("strategy 只能是 GREEDY 或 HUNGARIAN");
            await(engine.call(w -> {
                w.setStrategy(s);
                return true;
            }));
        }
        if (b.has("rebalance")) {
            boolean on = b.get("rebalance").getAsBoolean();
            await(engine.call(w -> {
                w.setRebalance(on);
                return true;
            }));
        }
        if (b.has("ratePerMin")) {
            double r = b.get("ratePerMin").getAsDouble();
            if (r < 0 || r > 120) throw new BadRequest("ratePerMin 应在 0..120");
            await(engine.call(w -> {
                w.config().tasks.ratePerMin = r;
                w.events().info(w.time(), "订单到达率设为 " + r + " 单/分钟");
                return true;
            }));
        }
        sendJson(ex, 200, configJson().toString());
    }

    private void createTask(HttpExchange ex) throws Exception {
        JsonObject b = readBody(ex);
        GridMap map = engine.map();
        int[] from = cellArg(b, "from");
        int[] to = cellArg(b, "to");
        Task t;
        try {
            t = await(engine.call(w -> w.createTask(map.index(from[0], from[1]), map.index(to[0], to[1]))));
        } catch (ExecutionException e) {
            throw new BadRequest(e.getCause().getMessage());
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("taskId", t.id);
        sendJson(ex, 200, o.toString());
    }

    private void roadblock(HttpExchange ex) throws Exception {
        JsonObject b = readBody(ex);
        GridMap map = engine.map();
        int[] c = cellArg(b, "cell");
        boolean on = !b.has("blocked") || b.get("blocked").getAsBoolean();
        try {
            await(engine.call(w -> {
                w.setRoadblock(map.index(c[0], c[1]), on);
                return true;
            }));
        } catch (ExecutionException e) {
            throw new BadRequest(e.getCause().getMessage());
        }
        sendJson(ex, 200, ok(on ? "已设置路障" : "已解除路障"));
    }

    private int[] cellArg(JsonObject b, String key) {
        if (!b.has(key) || !b.get(key).isJsonArray() || b.getAsJsonArray(key).size() != 2) {
            throw new BadRequest(key + " 应为 [x, y] 格子坐标");
        }
        JsonArray a = b.getAsJsonArray(key);
        int x = a.get(0).getAsInt(), y = a.get(1).getAsInt();
        if (!engine.map().inBounds(x, y)) throw new BadRequest(key + " 超出地图范围");
        return new int[]{x, y};
    }

    private JsonObject configJson() {
        JsonObject o = new JsonObject();
        o.addProperty("strategy", cfg.dispatch.strategy);
        o.addProperty("ratePerMin", cfg.tasks.ratePerMin);
        o.addProperty("timeScale", engine.timeScale());
        o.addProperty("paused", engine.paused());
        o.addProperty("taxis", cfg.taxis);
        o.addProperty("seed", cfg.seed);
        o.addProperty("tickHz", cfg.tickHz);
        o.addProperty("rebalance", cfg.dispatch.rebalance);
        Scenario sc = engine.scenario();
        o.addProperty("scenario", sc != null ? sc.name : null);
        o.addProperty("scenarioFile", sc != null ? engine.scenarioFile() : null);
        return o;
    }

    /**
     * 图像识别：请求体是一张 JPEG / PNG 图片，参数放在查询串里：
     * source（手机车的编号或 web）、x / y / theta（相机在地图上的位姿，省略时用 source 智能体的当前位姿）、
     * hfov（水平视场角，度）、h（目标真实高度，米）、scale（比例尺）、benchmark=1（附带单线程对比耗时）。
     * 识别在 HTTP 线程里完成（不占用仿真线程），结果再交给仿真线程并入地图。
     */
    private void visionDetect(HttpExchange ex) throws Exception {
        byte[] body = ex.getRequestBody().readNBytes(VisionService.MAX_BYTES + 1);
        String source = queryStr(ex, "source", "web");
        double[] pose = null;
        Double qx = queryDouble(ex, "x"), qy = queryDouble(ex, "y"), qt = queryDouble(ex, "theta");
        if (qx != null && qy != null) {
            pose = new double[]{qx, qy, qt != null ? qt : 0};
        } else if (!source.equals("web")) {
            pose = await(engine.call(w -> {
                Agent a = w.agent(source);
                return a == null ? null : new double[]{a.x, a.y, a.theta};
            }));
        }
        double hfov = orDefault(queryDouble(ex, "hfov"), 63);
        double h = orDefault(queryDouble(ex, "h"), 0.7);
        double scale = orDefault(queryDouble(ex, "scale"), 1.0);
        boolean bench = "1".equals(queryStr(ex, "benchmark", "0"));
        VisionService.Report r;
        try {
            r = vision.process(body, new VisionService.Params(source, pose, hfov, h, scale, bench));
        } catch (IllegalArgumentException e) {
            throw new BadRequest(e.getMessage());
        }
        int[] idx = vision.mappedCells(r);
        await(engine.call(w -> {
            w.reportVision(r.source(), r.objects().size(), r.summary(), idx, 2.0);
            return true;
        }));
        JsonObject o = gson.toJsonTree(r).getAsJsonObject();
        o.addProperty("ok", true);
        o.addProperty("posed", pose != null);
        sendJson(ex, 200, o.toString());
    }

    private byte[] sample;

    /** 网页"示例图片"按钮用的测试照片：程序画出的路面 + 一个橙色锥桶。 */
    private synchronized byte[] sampleImage() throws IOException {
        if (sample == null) {
            var img = com.zongc.dw.vision.SyntheticImages.roadScene(960, 720, 600, 230, 11);
            var bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "jpg", bos);
            sample = bos.toByteArray();
        }
        return sample;
    }

    /** 场景目录下的全部脚本（名称、说明、事件数），以及当前正在运行的脚本。 */
    private JsonObject scenariosJson() {
        JsonObject o = new JsonObject();
        o.addProperty("current", engine.scenarioFile());
        JsonArray list = new JsonArray();
        Path dir = cfg.resolve(cfg.scenarioDir);
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("file", f.getFileName().toString());
                    try {
                        Scenario sc = Scenario.load(f, engine.map());
                        item.addProperty("name", sc.name);
                        item.addProperty("description", sc.description);
                        item.addProperty("loop", sc.loop);
                        item.addProperty("events", sc.actions.size());
                        item.addProperty("durationSec", sc.duration());
                    } catch (IOException | IllegalArgumentException e) {
                        item.addProperty("error", e.getMessage());
                    }
                    list.add(item);
                }
            } catch (IOException e) {
                Log.error("读取场景目录失败", e);
            }
        }
        o.add("scenarios", list);
        return o;
    }

    /** 切换场景脚本（世界重置后从头开始）；file 为空表示停止脚本。只在考核开始之前使用。 */
    private void switchScenario(HttpExchange ex) throws IOException {
        JsonObject b = readBody(ex);
        String file = b.has("file") && !b.get("file").isJsonNull() ? b.get("file").getAsString().trim() : "";
        if (file.isEmpty()) {
            engine.switchScenario(null, null);
            sendJson(ex, 200, ok("已停止场景脚本，世界已重置"));
            return;
        }
        if (file.contains("/") || file.contains("\\") || file.contains("..")) throw new BadRequest("文件名不合法");
        Path f = cfg.resolve(cfg.scenarioDir).resolve(file);
        if (!Files.isRegularFile(f)) throw new BadRequest("找不到场景脚本: " + file);
        Scenario sc;
        try {
            sc = Scenario.load(f, engine.map());
        } catch (IllegalArgumentException e) {
            throw new BadRequest(e.getMessage());
        }
        engine.switchScenario(sc, file);
        sendJson(ex, 200, ok("已切换到场景脚本：" + sc.name + "（世界已重置）"));
    }

    private JsonObject infoJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", "MiniDiDi 数字世界 (DW)");
        o.addProperty("protocol", DeviceBridge.PROTOCOL_VERSION);
        o.addProperty("httpPort", cfg.http.port);
        o.addProperty("tcpPort", cfg.devices.tcpPort);
        o.addProperty("udpPort", cfg.devices.udpPort);
        JsonArray addrs = new JsonArray();
        NetUtil.lanAddresses().forEach(addrs::add);
        o.add("addresses", addrs);
        return o;
    }

    private static String buildMapJson(GridMap map) {
        JsonObject o = new JsonObject();
        o.addProperty("name", map.name());
        o.addProperty("width", map.width());
        o.addProperty("height", map.height());
        o.addProperty("resolution", map.resolution());
        JsonArray rows = new JsonArray();
        map.toRows().forEach(rows::add);
        o.add("rows", rows);
        return o.toString();
    }

    // ------------------------------------------------------------------ 静态文件

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
            sendJson(ex, 405, error("只支持 GET"));
            return;
        }
        String rel = URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (rel.equals("/") || rel.isEmpty()) rel = "/index.html";
        Path file = webRoot.resolve(rel.substring(1)).normalize();
        if (!file.startsWith(webRoot)) {
            sendJson(ex, 403, error("禁止访问"));
            return;
        }
        if (Files.isDirectory(file)) file = file.resolve("index.html");
        if (!Files.isRegularFile(file)) {
            sendJson(ex, 404, error("找不到文件: " + rel));
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, data.length == 0 ? -1 : data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static <T> T await(java.util.concurrent.CompletableFuture<T> f)
            throws InterruptedException, ExecutionException, TimeoutException {
        return f.get(3, TimeUnit.SECONDS);
    }

    private static JsonObject readBody(HttpExchange ex) throws IOException {
        String s = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(s);
            if (!e.isJsonObject()) throw new BadRequest("请求体必须是 JSON 对象");
            return e.getAsJsonObject();
        } catch (RuntimeException e) {
            if (e instanceof BadRequest) throw e;
            throw new BadRequest("请求体不是合法 JSON: " + e.getMessage());
        }
    }

    private static String queryStr(HttpExchange ex, String key, String def) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return def;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return def;
    }

    private static Double queryDouble(HttpExchange ex, String key) {
        String v = queryStr(ex, key, null);
        if (v == null || v.isBlank()) return null;
        try {
            double d = Double.parseDouble(v);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            throw new BadRequest(key + " 不是数字: " + v);
        }
    }

    private static double orDefault(Double v, double def) {
        return v != null ? v : def;
    }

    private static void sendBytes(HttpExchange ex, String type, byte[] data) {
        try {
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, data.length == 0 ? -1 : data.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
        } catch (IOException e) {
            Log.debug("HTTP 响应发送失败: " + e.getMessage());
        }
    }

    private static int queryInt(HttpExchange ex, String key, int def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return def;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try {
                    return Integer.parseInt(kv[1]);
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        return def;
    }

    private static void sendJson(HttpExchange ex, int code, String json) {
        try {
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
        } catch (IOException e) {
            Log.debug("HTTP 响应发送失败: " + e.getMessage());
        }
    }

    private static String ok(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", message);
        return o.toString();
    }

    private static String error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o.toString();
    }

    /** 客户端请求有误（返回 400）。 */
    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message);
        }
    }
}
