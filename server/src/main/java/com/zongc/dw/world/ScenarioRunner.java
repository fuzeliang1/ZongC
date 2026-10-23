package com.zongc.dw.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.util.Num;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在仿真线程中按时间执行 {@link Scenario}。每个仿真步调用一次 {@link #update(World)}。
 *
 * <ul>
 *   <li>时间以"本轮开始"为零点；{@code loop=true} 时最后一个事件之后再等 loopGapSec 秒重新开始，
 *       并自动撤掉本轮脚本放置的路障和障碍，方便无人值守长时间演示；</li>
 *   <li>未知障碍不会直接"砸"在车上：目标格 2.5 m 内有车或行人时推迟出现，最多等 30 s；</li>
 *   <li>事件附带的 text 会写入事件日志，并在界面上以横幅显示 8 秒。</li>
 * </ul>
 */
public final class ScenarioRunner {
    private static final double BANNER_SEC = 8;
    private static final double OBSTACLE_CLEARANCE = 2.5;
    private static final double MAX_DEFER_SEC = 30;

    /** 推送给前端的场景状态。 */
    public record View(String name, double elapsed, int step, int steps, Double nextIn, String next,
                       String banner, int loop) {}

    private record Deferred(Scenario.Action action, double since) {}

    private final Scenario scenario;
    private double start = Double.NaN;
    private int next;
    private int loop = 1;
    private String banner;
    private double bannerUntil;
    private final List<Deferred> deferred = new ArrayList<>();
    private final Set<Integer> touchedRoadblocks = new LinkedHashSet<>();
    private final Set<Integer> touchedObstacles = new LinkedHashSet<>();

    public ScenarioRunner(Scenario scenario) {
        this.scenario = scenario;
    }

    public Scenario scenario() {
        return scenario;
    }

    void update(World w) {
        if (Double.isNaN(start)) {
            start = w.time();
            w.events().info(w.time(), "场景脚本开始：" + scenario.name + "（" + scenario.actions.size() + " 个事件"
                    + (scenario.loop ? "，循环播放" : "") + "）");
        }
        double et = w.time() - start;
        retryDeferred(w);
        while (next < scenario.actions.size() && scenario.actions.get(next).t() <= et + 1e-9) {
            execute(w, scenario.actions.get(next));
            next++;
        }
        if (scenario.loop && next >= scenario.actions.size() && deferred.isEmpty()
                && et >= scenario.duration() + scenario.loopGapSec) {
            restoreWorld(w, true);
            start = w.time();
            next = 0;
            loop++;
            w.events().info(w.time(), "场景脚本第 " + loop + " 轮开始");
        }
    }

    private void execute(World w, Scenario.Action a) {
        if (a.action().equals("obstacle") && a.bool("on", true) && !clearOfAgents(w, a)) {
            deferred.add(new Deferred(a, w.time()));
            return;
        }
        if (a.text() != null) announce(w, a.text());
        GridMap map = w.map();
        try {
            switch (a.action()) {
                case "announce" -> {
                    // 只有文字
                }
                case "rate" -> {
                    double r = a.num("ratePerMin", w.config().tasks.ratePerMin);
                    w.config().tasks.ratePerMin = r;
                    w.events().info(w.time(), "订单到达率设为 " + Num.r1(r) + " 单/分钟");
                }
                case "strategy" -> w.setStrategy(a.str("value"));
                case "roadblock" -> {
                    int[] c = a.cell("cell");
                    int idx = map.index(c[0], c[1]);
                    boolean on = a.bool("blocked", true);
                    w.setRoadblock(idx, on);
                    if (on) touchedRoadblocks.add(idx);
                    else touchedRoadblocks.remove(idx);
                }
                case "obstacle" -> {
                    int[] c = a.cell("cell");
                    int idx = map.index(c[0], c[1]);
                    boolean on = a.bool("on", true);
                    w.perception().setHidden(w, idx, on);
                    touchedObstacles.add(idx);   // 撤走后也记着：新一轮开始时要清掉感知对它的结论
                }
                case "task" -> {
                    int[] f = a.cell("from"), t = a.cell("to");
                    w.createTask(map.index(f[0], f[1]), map.index(t[0], t[1]));
                }
                case "rush" -> {
                    int n = (int) a.num("count", 1), made = 0;
                    for (int i = 0; i < n; i++) {
                        int[] trip = w.randomTrip();
                        if (trip == null) continue;
                        w.createTask(trip[0], trip[1]);
                        made++;
                    }
                    w.events().info(w.time(), "订单潮：一次出现 " + made + " 个订单");
                }
                case "clear" -> restoreWorld(w, false);
                case "demand" -> w.setDemand(Demand.fromJson(a.args(), map));
                case "rebalance" -> w.setRebalance(a.bool("on", true));
                default -> w.events().warn(w.time(), "场景脚本：未知动作 " + a.action());
            }
        } catch (IllegalArgumentException e) {
            w.events().warn(w.time(), "场景脚本事件未能执行（" + a.action() + "）：" + e.getMessage());
        }
    }

    /** 未知障碍出现前，确认目标格附近没有车或行人（否则会凭空"砸"在车上）。 */
    private boolean clearOfAgents(World w, Scenario.Action a) {
        int[] c = a.cell("cell");
        double cx = (c[0] + 0.5) * w.map().resolution(), cy = (c[1] + 0.5) * w.map().resolution();
        for (Agent ag : w.agents()) {
            if (Math.hypot(ag.x - cx, ag.y - cy) < OBSTACLE_CLEARANCE * w.map().resolution()) return false;
        }
        return true;
    }

    private void retryDeferred(World w) {
        if (deferred.isEmpty()) return;
        List<Deferred> copy = new ArrayList<>(deferred);
        deferred.clear();
        for (Deferred d : copy) {
            if (clearOfAgents(w, d.action())) {
                execute(w, d.action());
            } else if (w.time() - d.since() > MAX_DEFER_SEC) {
                int[] c = d.action().cell("cell");
                w.events().warn(w.time(), "场景脚本：(" + c[0] + "," + c[1] + ") 附近一直有车，放弃放置障碍");
            } else {
                deferred.add(d);
            }
        }
    }

    /**
     * 撤掉本脚本放置的路障与障碍。
     *
     * @param forget true（新一轮开始）时同时清掉感知对这些格子的结论，让每一轮都从"系统不知道"开始；
     *               false（clear 动作）时只从真实世界中移除，系统要靠车辆感知才知道障碍已经没了
     */
    private void restoreWorld(World w, boolean forget) {
        for (int idx : new ArrayList<>(touchedRoadblocks)) {
            if (w.map().isBlocked(idx)) w.setRoadblock(idx, false);
        }
        touchedRoadblocks.clear();
        for (int idx : new ArrayList<>(touchedObstacles)) {
            w.perception().setHidden(w, idx, false);
            if (forget) w.perception().forget(w, idx);
        }
        touchedObstacles.clear();
        deferred.clear();
    }

    private void announce(World w, String text) {
        banner = text;
        bannerUntil = w.time() + BANNER_SEC;
        w.events().info(w.time(), "【场景】" + text);
    }

    View view(double now) {
        double et = Double.isNaN(start) ? 0 : now - start;
        Double nextIn = null;
        String nextText = null;
        if (next < scenario.actions.size()) {
            Scenario.Action a = scenario.actions.get(next);
            nextIn = Num.r1(Math.max(0, a.t() - et));
            nextText = a.text() != null ? a.text() : describe(a);
        } else if (scenario.loop) {
            nextIn = Num.r1(Math.max(0, scenario.duration() + scenario.loopGapSec - et));
            nextText = "第 " + (loop + 1) + " 轮开始";
        }
        String b = now <= bannerUntil ? banner : null;
        return new View(scenario.name, Num.r1(et), next, scenario.actions.size(), nextIn, nextText, b, loop);
    }

    /** 没有 text 的事件，在界面"下一步"里显示的默认说明。 */
    static String describe(Scenario.Action a) {
        JsonObject o = a.args();
        return switch (a.action()) {
            case "rate" -> "订单到达率改为 " + Num.r1(a.num("ratePerMin", 0)) + " 单/分钟";
            case "strategy" -> "派单策略切换为 " + a.str("value");
            case "roadblock" -> (a.bool("blocked", true) ? "设置路障 " : "解除路障 ") + cellText(o.get("cell"));
            case "obstacle" -> (a.bool("on", true) ? "出现未知障碍 " : "障碍消失 ") + cellText(o.get("cell"));
            case "task" -> "手动订单 " + cellText(o.get("from")) + " → " + cellText(o.get("to"));
            case "rush" -> "订单潮：" + (int) a.num("count", 1) + " 单";
            case "clear" -> "撤除全部路障与障碍";
            case "demand" -> "需求热点变化";
            case "rebalance" -> a.bool("on", true) ? "开启空车调度" : "关闭空车调度";
            default -> a.action();
        };
    }

    private static String cellText(JsonElement e) {
        if (e == null || !e.isJsonArray()) return "";
        return "(" + e.getAsJsonArray().get(0).getAsInt() + "," + e.getAsJsonArray().get(1).getAsInt() + ")";
    }
}
