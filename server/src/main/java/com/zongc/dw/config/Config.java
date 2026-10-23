package com.zongc.dw.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行配置（对应 server/config/*.json）。字段的初始值就是默认值，
 * 配置文件里没写的字段保持默认。相对路径以配置文件所在目录为基准。
 */
public final class Config {
    /** 地图文件。 */
    public String map = "../maps/city-5x4.json";
    /** 前端静态文件目录。 */
    public String webRoot = "../../web";
    /** 随机种子：相同种子 + 相同配置 = 可复现的仿真。 */
    public long seed = 20261023L;
    /** 数字世界中的出租车（数字车）数量，PPT 要求至少 5 辆。 */
    public int taxis = 8;
    /** 仿真步频（Hz），每步 dt = 1/tickHz 秒。 */
    public int tickHz = 20;
    /** 仿真倍速。接入 Matlab/手机等实时设备时必须为 1。 */
    public double timeScale = 1.0;
    /** 向浏览器推送快照的频率（Hz）。 */
    public int snapshotHz = 10;
    /** 启动时运行的场景脚本（相对于配置文件目录），为 null 时不运行脚本。命令行 --scenario 可覆盖。 */
    public String scenario = null;
    /** 场景脚本目录（相对于配置文件目录），网页上的场景列表从这里读取。 */
    public String scenarioDir = "../scenarios";

    public Vehicle vehicle = new Vehicle();
    public Traffic traffic = new Traffic();
    public Dispatch dispatch = new Dispatch();
    public Tasks tasks = new Tasks();
    public Devices devices = new Devices();
    public Http http = new Http();

    /** 车辆参数（内部仿真车使用，同时下发给外部设备作为参考）。 */
    public static final class Vehicle {
        public double bodyRadius = 0.3;    // 车身半径（米）
        public double vMax = 1.5;          // 最高速度（m/s）
        public double aAcc = 1.0;          // 加速度（m/s²）
        public double aDec = 1.5;          // 舒适减速度（m/s²）
        public double vTurn = 0.6;         // 转弯限速（m/s）
        public double stopMargin = 0.08;   // 停车点距离授权边界的额外余量（米）
        public double phoneRadius = 0.45;  // 手机（行人）占位半径（米）
    }

    /** 交通管控参数。 */
    public static final class Traffic {
        public int horizonCells = 3;          // 向前申请行驶授权的格数
        public double turnCost = 0.5;         // A* 转弯附加代价（米）
        public double priorityHoldSec = 3.0;  // 等待超过该时长的车可优先占用路口
        public double stuckRerouteSec = 12.0; // 受阻超过该时长则尝试绕行
    }

    /** 派单参数。 */
    public static final class Dispatch {
        public String strategy = "HUNGARIAN";  // GREEDY 或 HUNGARIAN
        public double intervalSec = 2.0;       // 派单周期（批量匹配需要积累一批订单）
        public double waitWeight = 0.3;        // 匈牙利算法中等待时间的权重（米/秒），防止订单"饿死"
        public boolean rebalance = false;      // 空车调度：把空车提前调往需求高的区域
        public double rebalanceIntervalSec = 8.0;
        public int zoneSize = 9;               // 需求统计的区域边长（格）
        public double demandTauSec = 180.0;    // 需求预测的衰减时间常数（秒）
    }

    /** 订单生成参数。 */
    public static final class Tasks {
        public double ratePerMin = 6.0;   // 泊松到达率（单/分钟）
        public int maxWaiting = 15;       // 等待中的订单上限
        public int minTripCells = 12;     // 起终点最小曼哈顿距离（格）
        public double dwellSec = 2.0;     // 上下车停留时间（秒）
        public int minCruiseCells = 8;    // 空车巡游目的地的最小距离（格）
    }

    /** 设备接入参数。 */
    public static final class Devices {
        public int tcpPort = 9000;
        public int udpPort = 9001;
        public double heartbeatTimeoutSec = 3.0;  // 超时未收到状态则判定离线
        public double worldHz = 5.0;              // 向设备广播周边车辆状态的频率
        public int maxTaxis = 20;                 // 设备接入时最多可扩充到的出租车数
    }

    public static final class Http {
        public int port = 8080;
    }

    // ---------- 读取 ----------

    private transient Path baseDir = Path.of(".").toAbsolutePath();

    public static Config load(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Config cfg = gson().fromJson(json, Config.class);
        if (cfg == null) cfg = new Config();
        cfg.baseDir = file.toAbsolutePath().getParent();
        cfg.validate();
        return cfg;
    }

    public static Config defaults() {
        return new Config();
    }

    /** 把配置中的相对路径解析为绝对路径。 */
    public Path resolve(String path) {
        return baseDir.resolve(path).normalize();
    }

    public void validate() {
        if (taxis < 1) throw new IllegalArgumentException("taxis 至少为 1");
        if (tickHz < 1 || tickHz > 200) throw new IllegalArgumentException("tickHz 应在 1..200");
        if (vehicle.bodyRadius <= 0 || vehicle.bodyRadius >= 0.5) {
            throw new IllegalArgumentException("bodyRadius 应在 (0, 0.5) 米之间（车道宽 1 格）");
        }
        if (!dispatch.strategy.equals("GREEDY") && !dispatch.strategy.equals("HUNGARIAN")) {
            throw new IllegalArgumentException("dispatch.strategy 只能是 GREEDY 或 HUNGARIAN");
        }
    }

    public static Gson gson() {
        return new GsonBuilder().create();
    }
}
