package com.zongc.dw.tools;

import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.map.MapLoader;
import com.zongc.dw.world.EventLog;
import com.zongc.dw.world.Metrics;
import com.zongc.dw.world.Scenario;
import com.zongc.dw.world.World;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线对比实验：不开网络、不按真实时间，尽快仿真若干分钟并输出指标。
 * 用于派单算法对比、空车调度对比、参数扫描，结果可直接放进技术报告的"效果评估"一章。
 *
 * <pre>
 * java -cp build/classes:lib/gson-2.13.1.jar com.zongc.dw.tools.Experiment \
 *      --minutes 30 --seeds 1,2,3 --strategies GREEDY,HUNGARIAN --taxis 8 --rate 6 --csv result.csv
 *
 * # 空车调度对比：同样的潮汐需求（场景脚本里的需求热点），关闭 / 开启调度
 * ... Experiment --scenario scenarios/rush-hour.json --strategies HUNGARIAN --rebalance off,on --minutes 30
 * </pre>
 *
 * 每组（策略 × 调度开关 × 种子）都从同一个随机种子开始，订单序列完全相同，比较才公平。
 */
public final class Experiment {

    public static void main(String[] args) throws Exception {
        String configPath = "config/default.json";
        double minutes = 10;
        List<Long> seeds = List.of(1L, 2L, 3L);
        List<String> strategies = List.of("GREEDY", "HUNGARIAN");
        List<Boolean> rebalance = null;
        Integer taxis = null;
        Double rate = null;
        String csv = null;
        String scenarioPath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = args[++i];
                case "--minutes" -> minutes = Double.parseDouble(args[++i]);
                case "--seeds" -> seeds = parseLongs(args[++i]);
                case "--strategies" -> strategies = List.of(args[++i].split(","));
                case "--rebalance" -> rebalance = parseOnOff(args[++i]);
                case "--taxis" -> taxis = Integer.parseInt(args[++i]);
                case "--rate" -> rate = Double.parseDouble(args[++i]);
                case "--scenario" -> scenarioPath = args[++i];
                case "--csv" -> csv = args[++i];
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    System.err.println("用法: Experiment [--minutes 10] [--seeds 1,2,3] [--strategies GREEDY,HUNGARIAN] "
                            + "[--rebalance off,on] [--taxis N] [--rate 单/分钟] [--scenario 场景.json] [--csv 输出.csv]");
                    return;
                }
            }
        }

        List<String> rows = new ArrayList<>();
        rows.add("strategy,rebalance,seed,taxis,ratePerMin,minutes,created,delivered,avgWait,maxWait,avgRide,avgTotal,"
                + "deliveredPerMin,emptyRatio,collisions,obstacleHits,barrierHits,reroutes,uturns,discoveries,"
                + "rebalanceMoves,dispatchMs,minCarGap,minWallGap,minBarrierGap,maxBlocked");
        System.out.printf("%-10s %4s %6s %6s %8s %8s %8s %8s %8s %6s %6s %6s %6s %8s %8s%n",
                "策略", "调度", "种子", "送达", "平均等待", "最长等待", "平均乘车", "单/分钟", "空驶率", "碰撞", "绕行",
                "掉头", "发现", "最小车距", "最长受阻");
        for (String strategy : strategies) {
            for (Boolean reb : rebalance != null ? rebalance : java.util.Collections.<Boolean>singletonList(null)) {
                for (long seed : seeds) {
                    Config cfg = Config.load(Path.of(configPath));
                    cfg.seed = seed;
                    cfg.dispatch.strategy = strategy;
                    if (taxis != null) cfg.taxis = taxis;
                    if (rate != null) cfg.tasks.ratePerMin = rate;
                    if (reb != null) cfg.dispatch.rebalance = reb;
                    GridMap map = MapLoader.load(cfg.resolve(cfg.map));
                    EventLog log = new EventLog(50);
                    log.setEcho(false);
                    World w = new World(cfg, map, log);
                    if (scenarioPath != null) {
                        w.setScenario(Scenario.load(Path.of(scenarioPath), map));
                    }
                    long steps = Math.round(minutes * 60 * cfg.tickHz);
                    long t0 = System.nanoTime();
                    for (long s = 0; s < steps; s++) {
                        w.step();
                        // 场景脚本可能在运行中开关调度；对比实验以命令行为准
                        if (reb != null && w.rebalanceOn() != reb) w.setRebalance(reb);
                    }
                    double sec = (System.nanoTime() - t0) / 1e9;
                    Metrics.View v = w.metricsView();
                    boolean rebOn = w.rebalanceOn();
                    System.out.printf("%-10s %4s %6d %6d %8.1f %8.1f %8.1f %8.2f %8.3f %6d %6d %6d %6d %8.3f %8.1f   (%.1fs)%n",
                            strategy, rebOn ? "开" : "关", seed, v.delivered(), v.avgWait(), v.maxWait(), v.avgRide(),
                            v.deliveredPerMin(), v.emptyRatio(), v.collisions() + v.obstacleHits() + v.barrierHits(),
                            v.reroutes(), v.uturns(), v.discoveries(), nz(v.minCarGap()), v.maxBlocked(), sec);
                    rows.add(String.join(",", strategy, rebOn ? "on" : "off", String.valueOf(seed),
                            String.valueOf(cfg.taxis), String.valueOf(cfg.tasks.ratePerMin), String.valueOf(minutes),
                            String.valueOf(v.created()), String.valueOf(v.delivered()), String.valueOf(v.avgWait()),
                            String.valueOf(v.maxWait()), String.valueOf(v.avgRide()), String.valueOf(v.avgTotal()),
                            String.valueOf(v.deliveredPerMin()), String.valueOf(v.emptyRatio()),
                            String.valueOf(v.collisions()), String.valueOf(v.obstacleHits()),
                            String.valueOf(v.barrierHits()), String.valueOf(v.reroutes()), String.valueOf(v.uturns()),
                            String.valueOf(v.discoveries()), String.valueOf(v.rebalanceMoves()),
                            String.valueOf(v.lastDispatchMs()), String.valueOf(nz(v.minCarGap())),
                            String.valueOf(nz(v.minWallGap())), String.valueOf(nz(v.minBarrierGap())),
                            String.valueOf(v.maxBlocked())));
                }
            }
        }
        if (csv != null) {
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Path.of(csv), StandardCharsets.UTF_8))) {
                rows.forEach(pw::println);
            }
            System.out.println("结果已写入 " + csv);
        }
    }

    private static double nz(Double v) {
        return v == null ? Double.NaN : v;
    }

    private static List<Long> parseLongs(String s) {
        List<Long> out = new ArrayList<>();
        for (String p : s.split(",")) out.add(Long.parseLong(p.trim()));
        return out;
    }

    private static List<Boolean> parseOnOff(String s) {
        List<Boolean> out = new ArrayList<>();
        for (String p : s.split(",")) {
            switch (p.trim().toLowerCase()) {
                case "on", "true", "1", "开" -> out.add(true);
                case "off", "false", "0", "关" -> out.add(false);
                default -> throw new IllegalArgumentException("--rebalance 只能是 on / off 的组合，例如 off,on");
            }
        }
        return out;
    }
}
