package com.zongc.dw.tools;

import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.map.MapLoader;
import com.zongc.dw.world.EventLog;
import com.zongc.dw.world.Metrics;
import com.zongc.dw.world.World;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线对比实验：不开网络、不按真实时间，尽快仿真若干分钟并输出指标。
 * 用于派单算法对比、参数扫描，结果可直接放进技术报告的"效果评估"一章。
 *
 * <pre>
 * java -cp build/classes:lib/gson-2.13.1.jar com.zongc.dw.tools.Experiment \
 *      --minutes 30 --seeds 1,2,3 --strategies GREEDY,HUNGARIAN --taxis 8 --rate 6 --csv result.csv
 * </pre>
 */
public final class Experiment {

    public static void main(String[] args) throws Exception {
        String configPath = "config/default.json";
        double minutes = 10;
        List<Long> seeds = List.of(1L, 2L, 3L);
        List<String> strategies = List.of("GREEDY", "HUNGARIAN");
        Integer taxis = null;
        Double rate = null;
        String csv = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = args[++i];
                case "--minutes" -> minutes = Double.parseDouble(args[++i]);
                case "--seeds" -> seeds = parseLongs(args[++i]);
                case "--strategies" -> strategies = List.of(args[++i].split(","));
                case "--taxis" -> taxis = Integer.parseInt(args[++i]);
                case "--rate" -> rate = Double.parseDouble(args[++i]);
                case "--csv" -> csv = args[++i];
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    return;
                }
            }
        }

        List<String> rows = new ArrayList<>();
        rows.add("strategy,seed,taxis,ratePerMin,minutes,created,delivered,avgWait,maxWait,avgRide,avgTotal,deliveredPerMin,emptyRatio,collisions,obstacleHits,reroutes,dispatchMs,minCarGap,minWallGap,maxBlocked");
        System.out.printf("%-10s %6s %6s %8s %8s %8s %8s %8s %6s %6s %8s %8s %8s%n",
                "策略", "种子", "送达", "平均等待", "最长等待", "平均乘车", "单/分钟", "空驶率", "碰撞", "绕行",
                "最小车距", "最小墙距", "最长受阻");
        for (String strategy : strategies) {
            for (long seed : seeds) {
                Config cfg = Config.load(Path.of(configPath));
                cfg.seed = seed;
                cfg.dispatch.strategy = strategy;
                if (taxis != null) cfg.taxis = taxis;
                if (rate != null) cfg.tasks.ratePerMin = rate;
                GridMap map = MapLoader.load(cfg.resolve(cfg.map));
                EventLog log = new EventLog(50);
                log.setEcho(false);
                World w = new World(cfg, map, log);
                long steps = Math.round(minutes * 60 * cfg.tickHz);
                long t0 = System.nanoTime();
                for (long s = 0; s < steps; s++) w.step();
                double sec = (System.nanoTime() - t0) / 1e9;
                Metrics.View v = w.snapshot(false, 1).metrics();
                System.out.printf("%-10s %6d %6d %8.1f %8.1f %8.1f %8.2f %8.3f %6d %6d %8.3f %8.3f %8.1f   (%.1fs)%n",
                        strategy, seed, v.delivered(), v.avgWait(), v.maxWait(), v.avgRide(), v.deliveredPerMin(),
                        v.emptyRatio(), v.collisions() + v.obstacleHits(), v.reroutes(),
                        nz(v.minCarGap()), nz(v.minWallGap()), v.maxBlocked(), sec);
                rows.add(String.join(",", strategy, String.valueOf(seed), String.valueOf(cfg.taxis),
                        String.valueOf(cfg.tasks.ratePerMin), String.valueOf(minutes), String.valueOf(v.created()),
                        String.valueOf(v.delivered()), String.valueOf(v.avgWait()), String.valueOf(v.maxWait()),
                        String.valueOf(v.avgRide()), String.valueOf(v.avgTotal()), String.valueOf(v.deliveredPerMin()),
                        String.valueOf(v.emptyRatio()), String.valueOf(v.collisions()), String.valueOf(v.obstacleHits()),
                        String.valueOf(v.reroutes()), String.valueOf(v.lastDispatchMs()),
                        String.valueOf(nz(v.minCarGap())), String.valueOf(nz(v.minWallGap())),
                        String.valueOf(v.maxBlocked())));
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
}
