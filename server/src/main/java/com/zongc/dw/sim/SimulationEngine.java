package com.zongc.dw.sim;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.map.MapLoader;
import com.zongc.dw.util.Log;
import com.zongc.dw.world.DeviceLink;
import com.zongc.dw.world.EventLog;
import com.zongc.dw.world.Scenario;
import com.zongc.dw.world.World;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 仿真引擎：单独一个线程按固定频率推进数字世界。
 *
 * <p>线程模型（重要）：World 只在仿真线程里访问。网络线程、HTTP 线程想读写世界时，
 * 一律通过 {@link #submit} / {@link #call} 把操作排进队列，由仿真线程在下一个节拍执行。
 * 这样既不需要到处加锁，也不会出现"算到一半数据被改"的并发问题。</p>
 */
public final class SimulationEngine {
    private final Config cfg;
    private final GridMap map;
    private final EventLog events = new EventLog(300);
    private final ConcurrentLinkedQueue<Consumer<World>> inbox = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService exec;
    private final SnapshotHub hub = new SnapshotHub();
    private final Gson gson = new Gson();

    private World world;
    /** 当前场景脚本（重置世界后自动从头再来）。 */
    private volatile Scenario scenario;
    private volatile String scenarioFile;
    private volatile boolean paused;
    private volatile double timeScale;
    private double stepAccumulator;
    private long tickCount;

    /** 配置中会被界面 / 场景脚本在运行时改动的参数的初始值，切换场景时恢复。 */
    private final double baseRate;
    private final String baseStrategy;
    private final boolean baseRebalance;

    public SimulationEngine(Config cfg, GridMap map) {
        this.cfg = cfg;
        this.map = map;
        this.timeScale = cfg.timeScale;
        this.baseRate = cfg.tasks.ratePerMin;
        this.baseStrategy = cfg.dispatch.strategy;
        this.baseRebalance = cfg.dispatch.rebalance;
        this.world = new World(cfg, map, events);
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        long period = Math.round(1_000_000.0 / cfg.tickHz);
        exec.scheduleAtFixedRate(this::tick, 0, period, TimeUnit.MICROSECONDS);
    }

    public void stop() {
        exec.shutdownNow();
    }

    public SnapshotHub hub() { return hub; }
    public Config config() { return cfg; }
    public GridMap map() { return map; }
    public EventLog events() { return events; }
    public boolean paused() { return paused; }
    public Scenario scenario() { return scenario; }
    public String scenarioFile() { return scenarioFile; }

    /**
     * 设置启动时的场景脚本（在 {@link #start()} 之前调用）。
     *
     * @param file 场景文件名，只用于界面显示
     */
    public void setInitialScenario(Scenario s, String file) {
        scenario = s;
        scenarioFile = file;
        world.setScenario(s);
    }

    /** 切换场景脚本（null 表示不运行脚本）：世界会重置，脚本从头开始。 */
    public void switchScenario(Scenario s, String file) {
        submit(w -> {
            scenario = s;
            scenarioFile = file;
            cfg.tasks.ratePerMin = baseRate;
            cfg.dispatch.strategy = baseStrategy;
            cfg.dispatch.rebalance = baseRebalance;
            events.info(w.time(), s != null ? "切换场景脚本：" + s.name : "停止场景脚本");
        });
        reset(null);
    }
    public double timeScale() { return timeScale; }

    public void setPaused(boolean p) {
        paused = p;
        submit(w -> events.info(w.time(), p ? "仿真暂停" : "仿真继续"));
    }

    public void setTimeScale(double s) {
        timeScale = Math.max(0.1, Math.min(50, s));
        submit(w -> events.info(w.time(), "仿真倍速设为 " + timeScale + "×"));
    }

    /** 把一个操作排进仿真线程执行（异步）。 */
    public void submit(Consumer<World> cmd) {
        inbox.add(cmd);
    }

    /** 在仿真线程中执行并返回结果（给 HTTP 接口用）。 */
    public <T> CompletableFuture<T> call(Function<World, T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        inbox.add(w -> {
            try {
                f.complete(fn.apply(w));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** 重置世界（可换随机种子）。已连接的设备会自动重新绑定到新世界。 */
    public void reset(Long newSeed) {
        submit(old -> {
            Map<DeviceLink, JsonObject> regs = old.devices().registrations();
            if (newSeed != null) cfg.seed = newSeed;
            for (int i = 0; i < map.size(); i++) map.setBlocked(i, false);
            events.warn(old.time(), "仿真重置（种子 " + cfg.seed + "）");
            world = new World(cfg, map, events);
            if (scenario != null) world.setScenario(scenario);
            stepAccumulator = 0;
            regs.forEach((link, msg) -> {
                if (link.isOpen()) world.onDeviceMessage(link, msg);
            });
        });
    }

    private void tick() {
        try {
            Consumer<World> cmd;
            while ((cmd = inbox.poll()) != null) {
                try {
                    cmd.accept(world);
                } catch (Throwable t) {
                    Log.error("处理命令出错", t);
                }
            }
            if (!paused) {
                stepAccumulator += timeScale;
                int steps = (int) stepAccumulator;
                stepAccumulator -= steps;
                for (int i = 0; i < steps; i++) world.step();
            }
            world.afterTick();
            tickCount++;
            int every = Math.max(1, cfg.tickHz / Math.max(1, cfg.snapshotHz));
            if (tickCount % every == 0) {
                hub.publish(gson.toJson(world.snapshot(paused, timeScale)));
            }
        } catch (Throwable t) {
            Log.error("仿真步出错", t);
        }
    }

    /** 从配置文件构造（DwServer 和测试共用）。 */
    public static SimulationEngine fromConfig(Config cfg) throws java.io.IOException {
        GridMap map = MapLoader.load(cfg.resolve(cfg.map));
        return new SimulationEngine(cfg, map);
    }
}
