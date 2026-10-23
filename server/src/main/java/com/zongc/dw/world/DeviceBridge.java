package com.zongc.dw.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.plan.Route;
import com.zongc.dw.util.Num;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备接入的业务逻辑（协议语义），在仿真线程中运行。网络收发在 device 包中实现。
 *
 * <p>设备分两类：</p>
 * <ul>
 *   <li>出租车驱动（matlab / gazebo / generic）：绑定一辆数字车，服务端下发 path 与 permit，
 *       设备负责控制车辆沿路线行驶并上报 state —— 数字车即该设备的数字孪生。</li>
 *   <li>自由智能体（phone）：新建一个不参与派单的智能体，位置完全由设备上报。</li>
 * </ul>
 * <p>设备断开或心跳超时后，出租车由数字世界内部仿真接管，系统继续运行。</p>
 */
public final class DeviceBridge {
    public static final int PROTOCOL_VERSION = 1;

    private final World w;
    private final Map<DeviceLink, Agent> bound = new LinkedHashMap<>();
    private final Map<DeviceLink, JsonObject> registrations = new LinkedHashMap<>();
    private long lastWorldBroadcast;
    private long udpIgnored;

    DeviceBridge(World w) {
        this.w = w;
    }

    /** 当前所有已注册连接及其注册消息（重置世界时用于重新绑定）。 */
    public Map<DeviceLink, JsonObject> registrations() {
        return new LinkedHashMap<>(registrations);
    }

    public int boundCount() {
        return bound.size();
    }

    // ---------------------------------------------------------------- 入站

    void onMessage(DeviceLink link, JsonObject msg) {
        String type = str(msg, "type");
        if (type == null) {
            error(link, "消息缺少 type 字段");
            return;
        }
        Agent a = bound.get(link);
        switch (type) {
            case "register" -> onRegister(link, msg);
            case "state" -> {
                if (a == null) error(link, "请先发送 register 消息");
                else applyState(a, msg);
            }
            case "ping" -> {
                if (a != null) a.lastSeenNanos = System.nanoTime();
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                pong.addProperty("t", Num.r3(w.time()));
                link.send(pong.toString());
            }
            case "event" -> {
                if (a != null) {
                    a.lastSeenNanos = System.nanoTime();
                    w.events().info(w.time(), a.id + " 设备事件：" + str(msg, "event"));
                }
            }
            case "sensor" -> {
                // 预留：手机原始传感器数据（服务端位姿估计时使用），目前只当作心跳
                if (a != null) a.lastSeenNanos = System.nanoTime();
            }
            default -> error(link, "未知消息类型: " + type);
        }
    }

    void onUdp(JsonObject msg, String remote) {
        String id = str(msg, "agentId");
        Agent a = id == null ? null : w.agent(id);
        if (!"state".equals(str(msg, "type")) || a == null || a.link == null) {
            udpIgnored++;
            return;
        }
        applyState(a, msg);
    }

    void onClosed(DeviceLink link) {
        registrations.remove(link);
        Agent a = bound.remove(link);
        if (a == null) return;
        if (a.taxi) {
            if (a.link == link) {
                w.setDriver(a, DriverKind.INTERNAL, null);
                w.events().warn(w.time(), "设备 " + link.remote() + " 断开，" + a.id + " 由数字世界接管");
            }
        } else {
            w.removeAgent(a);
            w.events().warn(w.time(), "自由智能体 " + a.id + " 离线");
        }
    }

    private void onRegister(DeviceLink link, JsonObject msg) {
        if (bound.containsKey(link)) {
            error(link, "该连接已经注册过");
            return;
        }
        String kind = str(msg, "kind");
        DriverKind dk = DriverKind.fromKind(kind);
        String wanted = str(msg, "agentId");
        boolean free = dk == DriverKind.PHONE || "free".equals(str(msg, "role"));
        GridMap map = w.map();
        Agent a;
        if (free) {
            double x = num(msg, "x", map.width() * map.resolution() / 2);
            double y = num(msg, "y", map.height() * map.resolution() / 2);
            a = w.addFree(wanted, x, y, num(msg, "theta", 0));
            a.driver = dk;
            a.link = link;
            w.events().info(w.time(), "接入自由智能体 " + a.id + "（" + kindLabel(kind) + "@" + link.remote() + "）");
        } else {
            a = pickTaxi(wanted);
            if (a == null) {
                error(link, "没有可绑定的数字车（已达到 maxTaxis 上限）");
                link.close("no taxi available");
                return;
            }
            w.setDriver(a, dk == DriverKind.PHONE ? DriverKind.GENERIC : dk, link);
            w.events().info(w.time(), "设备 " + kindLabel(kind) + "@" + link.remote() + " 接管 " + a.id + "（数字孪生）");
        }
        a.lastSeenNanos = System.nanoTime();
        bound.put(link, a);
        registrations.put(link, msg.deepCopy());
        link.send(welcome(a).toString());
        if (a.taxi) {
            sendPath(a);
            sendPermit(a);
        }
    }

    /**
     * 选择要绑定的出租车：
     * <ol>
     *   <li>请求了 car-N 且该车不存在 → 新建 car-N（不会抢占别的设备将要请求的编号）；</li>
     *   <li>请求的车空闲 → 绑定；被一个已失联（1 秒无消息）的旧连接占用 → 新连接接管（便于设备重启）；</li>
     *   <li>请求的车正被活跃设备使用 → 不抢占，改为分配其他空闲车，避免两个设备互相踢下线；</li>
     *   <li>没有指定编号 → 编号最小的空闲车，没有空闲车时新建（不超过 maxTaxis）。</li>
     * </ol>
     */
    private Agent pickTaxi(String wanted) {
        if (wanted != null) {
            Agent a = w.agent(wanted);
            if (a == null && wanted.matches("car-\\d+") && taxiCount() < w.config().devices.maxTaxis) {
                a = w.addTaxi(wanted);
            }
            if (a != null && a.taxi) {
                if (a.link == null) return a;
                boolean stale = !a.link.isOpen() || System.nanoTime() - a.lastSeenNanos > 1_000_000_000L;
                if (stale) {
                    DeviceLink old = a.link;
                    old.close("被新连接接管");
                    onClosed(old);
                    return a;
                }
                w.events().warn(w.time(), "请求的 " + wanted + " 正被 " + a.link.remote() + " 使用，改为分配其他数字车");
            }
        }
        for (Agent a : w.agents()) {
            if (a.taxi && a.link == null) return a;
        }
        return taxiCount() < w.config().devices.maxTaxis ? w.addTaxi(null) : null;
    }

    private int taxiCount() {
        int n = 0;
        for (Agent a : w.agents()) if (a.taxi) n++;
        return n;
    }

    private void applyState(Agent a, JsonObject msg) {
        double x = num(msg, "x", Double.NaN);
        double y = num(msg, "y", Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y)) return;
        double theta = num(msg, "theta", a.theta);
        a.x = x;
        a.y = y;
        a.theta = Route.normalize(Double.isFinite(theta) ? theta : a.theta);
        a.v = num(msg, "v", 0);
        a.omega = num(msg, "w", 0);
        a.lastSeenNanos = System.nanoTime();
        a.stateCount++;
    }

    // ---------------------------------------------------------------- 出站

    /** 每个墙钟节拍调用：心跳超时检查、下发路线 / 授权、广播周边车辆。 */
    void update() {
        long now = System.nanoTime();
        long timeout = (long) (w.config().devices.heartbeatTimeoutSec * 1e9);
        for (Map.Entry<DeviceLink, Agent> e : new ArrayList<>(bound.entrySet())) {
            DeviceLink link = e.getKey();
            Agent a = e.getValue();
            if (!link.isOpen()) {
                onClosed(link);
            } else if (now - a.lastSeenNanos > timeout) {
                w.events().warn(w.time(), a.id + " 心跳超时（" + w.config().devices.heartbeatTimeoutSec + " s 未收到状态）");
                link.close("heartbeat timeout");
                onClosed(link);
            }
        }
        for (Agent a : bound.values()) {
            if (!a.taxi || a.route == null) continue;
            if (a.routeDirty || a.routeId != a.lastSentRouteId) sendPath(a);
            if (a.permitDirty || a.authority != a.lastSentAuthority) sendPermit(a);
        }
        double hz = w.config().devices.worldHz;
        if (hz > 0 && now - lastWorldBroadcast >= (long) (1e9 / hz) && !bound.isEmpty()) {
            lastWorldBroadcast = now;
            String msg = worldMessage().toString();
            for (Map.Entry<DeviceLink, Agent> e : bound.entrySet()) {
                if (e.getValue().taxi) e.getKey().send(msg);
            }
        }
    }

    private JsonObject welcome(Agent a) {
        Config cfg = w.config();
        GridMap map = w.map();
        JsonObject o = new JsonObject();
        o.addProperty("type", "welcome");
        o.addProperty("protocol", PROTOCOL_VERSION);
        o.addProperty("agentId", a.id);
        o.addProperty("role", a.taxi ? "taxi" : "free");
        o.addProperty("driver", a.driver.name());
        JsonObject pose = new JsonObject();
        pose.addProperty("x", Num.r3(a.x));
        pose.addProperty("y", Num.r3(a.y));
        pose.addProperty("theta", Num.r3(a.theta));
        pose.addProperty("v", Num.r3(a.v));
        o.add("pose", pose);
        JsonObject p = new JsonObject();
        p.addProperty("bodyRadius", cfg.vehicle.bodyRadius);
        p.addProperty("vMax", cfg.vehicle.vMax);
        p.addProperty("aAcc", cfg.vehicle.aAcc);
        p.addProperty("aDec", cfg.vehicle.aDec);
        p.addProperty("vTurn", cfg.vehicle.vTurn);
        p.addProperty("stopMargin", cfg.vehicle.stopMargin);
        p.addProperty("stateHz", 10);
        p.addProperty("heartbeatTimeoutSec", cfg.devices.heartbeatTimeoutSec);
        o.add("params", p);
        JsonObject m = new JsonObject();
        m.addProperty("name", map.name());
        m.addProperty("width", map.width());
        m.addProperty("height", map.height());
        m.addProperty("resolution", map.resolution());
        o.add("map", m);
        o.addProperty("t", Num.r3(w.time()));
        return o;
    }

    private void sendPath(Agent a) {
        Route r = a.route;
        GridMap map = w.map();
        JsonObject o = new JsonObject();
        o.addProperty("type", "path");
        o.addProperty("agentId", a.id);
        o.addProperty("routeId", a.routeId);
        o.addProperty("purpose", a.purpose.name());
        o.addProperty("status", a.status.name());
        o.addProperty("taskId", a.task != null ? a.task.id : null);
        JsonArray cells = new JsonArray();
        for (int i = 0; i <= r.last(); i++) cells.add(pair(map.cx(r.cell(i)), map.cy(r.cell(i))));
        double[][] poly = r.devicePolyline();
        JsonArray wps = new JsonArray();
        for (int i = 0; i < poly[0].length; i++) wps.add(pair(Num.r3(poly[0][i]), Num.r3(poly[1][i])));
        o.add("cells", cells);
        o.add("waypoints", wps);
        o.addProperty("entryDir", r.entryDir() != null ? r.entryDir().name() : null);
        a.link.send(o.toString());
        a.routeDirty = false;
        a.lastSentRouteId = a.routeId;
        a.permitDirty = true;   // 新路线之后必须紧跟一条授权
    }

    private void sendPermit(Agent a) {
        Route r = a.route;
        double stopS = w.traffic().stopPolylineS(a);
        var p = r.poseAt(Math.max(0, w.traffic().stopS(a)));
        double[] stop = {p.x(), p.y()};
        JsonObject o = new JsonObject();
        o.addProperty("type", "permit");
        o.addProperty("agentId", a.id);
        o.addProperty("routeId", a.routeId);
        o.addProperty("until", a.authority);
        o.addProperty("stopS", Num.r3(stopS));
        o.add("stop", pair(Num.r3(stop[0]), Num.r3(stop[1])));
        o.addProperty("final", a.authority >= r.last());
        a.link.send(o.toString());
        a.permitDirty = false;
        a.lastSentAuthority = a.authority;
    }

    private JsonObject worldMessage() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "world");
        o.addProperty("t", Num.r3(w.time()));
        JsonArray arr = new JsonArray();
        for (Agent a : w.agents()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", a.id);
            x.addProperty("x", Num.r3(a.x));
            x.addProperty("y", Num.r3(a.y));
            x.addProperty("theta", Num.r3(a.theta));
            x.addProperty("v", Num.r3(a.v));
            x.addProperty("r", w.traffic().radiusOf(a));
            x.addProperty("taxi", a.taxi);
            arr.add(x);
        }
        o.add("agents", arr);
        return o;
    }

    private static void error(DeviceLink link, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("message", message);
        link.send(o.toString());
    }

    // ---------------------------------------------------------------- 工具

    private static String kindLabel(String kind) {
        return kind == null ? "generic" : kind;
    }

    private static JsonArray pair(Number a, Number b) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        return arr;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    static double num(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return def;
        return e.getAsDouble();
    }

    List<String> boundSummary() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<DeviceLink, Agent> e : bound.entrySet()) out.add(e.getValue().id + "@" + e.getKey().remote());
        return out;
    }
}
