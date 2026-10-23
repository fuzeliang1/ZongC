package com.zongc.dw.world;

import com.google.gson.JsonObject;
import com.zongc.dw.config.Config;
import com.zongc.dw.dispatch.Dispatcher;
import com.zongc.dw.map.Dir;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.plan.AStarPlanner;
import com.zongc.dw.plan.DistanceField;
import com.zongc.dw.plan.Route;
import com.zongc.dw.traffic.TrafficController;
import com.zongc.dw.util.Num;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 数字世界（Digital World, DW）：地图 + 全部智能体 + 订单 + 仿真时钟。
 *
 * <p>每个仿真步 {@link #step()} 依次执行：生成订单 → 内部车运动 → 到站处理 → 周期派单
 * → 交通管控（行驶授权）→ 碰撞监测。外部设备（Matlab/手机/Gazebo）的状态由
 * {@link DeviceBridge} 在步与步之间写入。</p>
 *
 * <p>线程约定：World 只在仿真线程中被访问（见 sim.SimulationEngine），因此内部不加锁。</p>
 */
public final class World {
    private static final double ARRIVE_TOL = 0.25;

    private final Config cfg;
    private final GridMap map;
    private final Random rng;
    private final double dt;
    private final AStarPlanner planner;
    private final TrafficController traffic;
    private final InternalDriver driver;
    private final TaskGenerator generator;
    private final CollisionMonitor collisions = new CollisionMonitor();
    private final DeviceBridge devices;
    private final Metrics metrics = new Metrics();
    private final EventLog events;

    private final List<Agent> agents = new ArrayList<>();
    private final Map<String, Agent> byId = new LinkedHashMap<>();
    private final List<Task> activeTasks = new ArrayList<>();
    private Dispatcher dispatcher;
    private double time;
    private double dispatchTimer;
    private int taskSeq;
    private int nextTaxiNum = 1;
    private int nextFreeNum = 1;

    public World(Config cfg, GridMap map, EventLog events) {
        this.cfg = cfg;
        this.map = map;
        this.events = events;
        this.rng = new Random(cfg.seed);
        this.dt = 1.0 / cfg.tickHz;
        this.planner = new AStarPlanner(map, cfg.traffic.turnCost * map.resolution());
        this.traffic = new TrafficController(map, cfg);
        this.driver = new InternalDriver(cfg.vehicle);
        this.generator = new TaskGenerator(cfg.tasks, map, rng);
        this.devices = new DeviceBridge(this);
        this.dispatcher = Dispatcher.of(cfg.dispatch.strategy, cfg.dispatch);
        for (int i = 0; i < cfg.taxis; i++) {
            if (addTaxi(null) == null) break;
        }
        events.info(time, "数字世界启动：地图 " + map.name() + "（" + map.width() + "×" + map.height() + "），"
                + agents.size() + " 辆数字车，派单策略 " + dispatcher.name());
    }

    // =====================================================================
    // 访问器
    // =====================================================================

    public Config config() { return cfg; }
    public GridMap map() { return map; }
    public double time() { return time; }
    public double dt() { return dt; }
    public Metrics metrics() { return metrics; }
    public EventLog events() { return events; }
    public TrafficController traffic() { return traffic; }
    public DeviceBridge devices() { return devices; }
    public List<Agent> agents() { return Collections.unmodifiableList(agents); }
    public Agent agent(String id) { return byId.get(id); }
    public List<Task> activeTasks() { return Collections.unmodifiableList(activeTasks); }
    public String strategy() { return dispatcher.name(); }
    Random rng() { return rng; }

    public void setStrategy(String strategy) {
        dispatcher = Dispatcher.of(strategy, cfg.dispatch);
        cfg.dispatch.strategy = strategy;
        events.info(time, "派单策略切换为 " + strategy);
    }

    // =====================================================================
    // 仿真主循环
    // =====================================================================

    /** 推进一个仿真步（dt = 1/tickHz 秒）。 */
    public void step() {
        time += dt;
        generator.update(this, dt);
        for (Agent a : agents) {
            if (a.taxi && a.internal()) driver.advance(a, dt, traffic.stopS(a));
        }
        updateOdometry();
        handleArrivals();
        dispatchTimer += dt;
        if (dispatchTimer >= cfg.dispatch.intervalSec - 1e-9) {
            dispatchTimer = 0;
            dispatch();
        }
        TrafficController.Result r = traffic.update(agents, time, dt, metrics);
        for (Agent a : agents) {
            if (!Double.isNaN(a.blockedSince)) metrics.maxBlocked = Math.max(metrics.maxBlocked, time - a.blockedSince);
        }
        for (int i = 0; i < r.stuck().size(); i++) reroute(r.stuck().get(i), r.stuckBlockingCells().get(i));
        for (Agent a : r.offRoute()) {
            events.warn(time, a.id + " 偏离路线，从当前位置重新规划");
            a.route = null;
            replanForStatus(a);
        }
        collisions.check(this);
    }

    /** 每个墙钟节拍调用一次（暂停时也调用）：设备心跳检查、下发路线/授权/周边车辆。 */
    public void afterTick() {
        devices.update();
    }

    private void updateOdometry() {
        for (Agent a : agents) {
            if (!a.taxi) continue;
            double ds = Math.hypot(a.x - a.odoX, a.y - a.odoY);
            a.odoX = a.x;
            a.odoY = a.y;
            if (ds > 1.0) continue;   // 设备接管等造成的跳变不计入里程
            if (a.status == AgentStatus.TO_DROPOFF) {
                a.distLoaded += ds;
                metrics.distLoaded += ds;
            } else {
                a.distEmpty += ds;
                metrics.distEmpty += ds;
            }
        }
    }

    // =====================================================================
    // 到站、上下车
    // =====================================================================

    private void handleArrivals() {
        for (Agent a : agents) {
            if (!a.taxi) continue;
            if (a.status == AgentStatus.BOARDING || a.status == AgentStatus.ALIGHTING) {
                a.dwell -= dt;
                if (a.dwell <= 0) finishDwell(a);
                continue;
            }
            if (a.replanPending) {
                if (time >= a.replanRetryAt) replanForStatus(a);
                continue;
            }
            if (a.route != null && arrived(a)) onRouteFinished(a);
        }
    }

    private boolean stoppedAtEnd(Agent a) {
        Route r = a.route;
        if (r == null || a.progress < r.last() || a.authority < r.last()) return false;
        if (a.internal()) return a.s >= r.length() - 1e-6;
        return Math.hypot(a.x - r.centerX(r.last()), a.y - r.centerY(r.last())) < ARRIVE_TOL * map.resolution()
                && Math.abs(a.v) < 0.2;
    }

    private boolean arrived(Agent a) {
        return stoppedAtEnd(a) && (!a.internal() || a.v == 0);
    }

    private void onRouteFinished(Agent a) {
        Task t = a.task;
        switch (a.purpose) {
            case CRUISE -> startCruise(a);
            case PICKUP -> {
                if (t == null) { startCruise(a); return; }
                t.state = Task.State.PICKED;
                t.pickedAt = time;
                metrics.onPicked(t);
                a.status = AgentStatus.BOARDING;
                a.dwell = cfg.tasks.dwellSec;
                events.info(time, a.id + " 接到乘客 " + t.id + "（等待 " + Num.r1(t.pickedAt - t.createdAt) + " s）");
            }
            case DROPOFF -> {
                if (t == null) { startCruise(a); return; }
                t.state = Task.State.DELIVERED;
                t.deliveredAt = time;
                metrics.onDelivered(t);
                a.delivered++;
                activeTasks.remove(t);
                a.status = AgentStatus.ALIGHTING;
                a.dwell = cfg.tasks.dwellSec;
                events.info(time, a.id + " 送达 " + t.id + "（乘车 " + Num.r1(t.deliveredAt - t.pickedAt) + " s）");
            }
        }
    }

    private void finishDwell(Agent a) {
        if (a.status == AgentStatus.BOARDING) {
            a.status = AgentStatus.TO_DROPOFF;
            if (!planRoute(a, a.task.dropoff, Purpose.DROPOFF, null)) markReplan(a);
        } else {
            a.task = null;
            a.status = AgentStatus.CRUISING;
            startCruise(a);
        }
    }

    /** 根据当前状态重新规划（用于失败重试、偏离路线、设备接管后）。 */
    private void replanForStatus(Agent a) {
        a.replanPending = false;
        boolean ok = switch (a.status) {
            case TO_PICKUP -> a.task != null && planRoute(a, a.task.pickup, Purpose.PICKUP, null);
            case TO_DROPOFF -> a.task != null && planRoute(a, a.task.dropoff, Purpose.DROPOFF, null);
            case CRUISING -> startCruise(a);
            default -> true;
        };
        if (!ok) {
            if (a.status == AgentStatus.TO_PICKUP && a.task != null) {
                // 接不到乘客：把订单退回等待队列，车辆恢复空车
                Task t = a.task;
                t.state = Task.State.WAITING;
                t.agentId = null;
                a.task = null;
                a.status = AgentStatus.CRUISING;
                events.warn(time, a.id + " 无法到达 " + t.id + " 的上车点，订单退回等待队列");
                if (startCruise(a)) return;
            }
            markReplan(a);
        }
    }

    private void markReplan(Agent a) {
        a.replanPending = true;
        a.replanRetryAt = time + 1.0;
    }

    // =====================================================================
    // 路线规划
    // =====================================================================

    /** 空车巡游：随机选一个较远的车道格作为目的地。 */
    private boolean startCruise(Agent a) {
        int ref = a.route != null ? a.route.cell(Math.max(a.authority, a.progress)) : currentCell(a);
        int[] lanes = map.laneCells();
        for (int tries = 0; tries < 40; tries++) {
            int g = lanes[rng.nextInt(lanes.length)];
            if (!map.passable(g) || g == ref) continue;
            if (manhattan(g, ref) < cfg.tasks.minCruiseCells) continue;
            if (planRoute(a, g, Purpose.CRUISE, null)) return true;
        }
        markReplan(a);
        return false;
    }

    /**
     * 为车辆规划去 goal 的新路线。
     *
     * <p>关键点：车辆行驶中重规划时，已经获得授权的那一段（当前格 … 授权终点）保持不变，
     * 新路线从授权终点接出去。这样车辆运动连续，已经预约的格子也不会作废。</p>
     */
    boolean planRoute(Agent a, int goal, Purpose purpose, Map<Integer, Double> penalty) {
        Route old = a.route;
        int[] prefix;
        Dir entry;
        Dir heading;
        int newAuthority;
        double newS = 0;
        if (old == null) {
            int start = currentCell(a);
            if (start < 0) return false;
            prefix = new int[]{start};
            entry = null;
            heading = map.isLane(start) ? map.type(start).laneDir : Dir.fromAngle(a.theta);
            newAuthority = 0;
        } else if (stoppedAtEnd(a)) {
            int start = old.cell(old.last());
            prefix = new int[]{start};
            entry = null;
            heading = old.dirInto(old.last()) != null ? old.dirInto(old.last()) : Dir.fromAngle(a.theta);
            newAuthority = 0;
        } else {
            int p = a.progress;
            int k = Math.max(a.authority, a.progress);
            prefix = new int[k - p + 1];
            for (int i = p; i <= k; i++) prefix[i - p] = old.cell(i);
            entry = old.dirInto(p);
            heading = old.dirInto(k) != null ? old.dirInto(k) : Dir.fromAngle(a.theta);
            newAuthority = k - p;
            newS = Math.max(0, a.s - old.sStart(p));
        }

        int[] tail = planner.plan(prefix[prefix.length - 1], heading, goal, penalty);
        if (tail == null) return false;
        int[] cells = new int[prefix.length + tail.length - 1];
        System.arraycopy(prefix, 0, cells, 0, prefix.length);
        System.arraycopy(tail, 1, cells, prefix.length, tail.length - 1);

        a.route = new Route(map, cells, entry);
        a.routeId++;
        a.purpose = purpose;
        a.goal = goal;
        a.progress = 0;
        a.authority = newAuthority;
        a.s = a.internal() ? Math.min(newS, a.route.length()) : 0;
        a.blockedSince = Double.NaN;
        a.offRouteTime = 0;
        a.replanPending = false;
        a.routeDirty = true;
        a.permitDirty = true;
        return true;
    }

    /**
     * 把路线"剪掉"已经走过的部分，让路线从车辆当前所在格开始（几何不变）。
     * 设备接管行驶中的车辆时调用，这样设备收到的路线起点就是车辆当前位置。
     */
    private void rebaseRoute(Agent a) {
        Route r = a.route;
        int p = a.progress;
        if (r == null || p <= 0) return;
        int[] cells = java.util.Arrays.copyOfRange(r.cells(), p, r.size());
        Route nr = new Route(map, cells, r.dirInto(p));
        a.s = Math.max(0, a.s - r.sStart(p));
        a.authority = Math.max(0, a.authority - p);
        a.progress = 0;
        a.route = nr;
        a.routeId++;
        a.routeDirty = true;
        a.permitDirty = true;
    }

    /** 受阻过久：给阻挡格加惩罚后重新规划，尝试绕行。 */
    private void reroute(Agent a, int blockingCell) {
        a.lastRerouteAt = time;
        Map<Integer, Double> penalty = new HashMap<>();
        penalty.put(blockingCell, 40.0 * map.resolution());
        int goal = a.goal;
        if (a.status == AgentStatus.TO_PICKUP && a.task != null) goal = a.task.pickup;
        if (a.status == AgentStatus.TO_DROPOFF && a.task != null) goal = a.task.dropoff;
        if (goal < 0) return;
        int[] before = a.route.cells();
        if (planRoute(a, goal, a.purpose, penalty)) {
            metrics.reroutes++;
            boolean changed = !java.util.Arrays.equals(before, a.route.cells());
            events.info(time, a.id + " 受阻超过 " + Num.r1(cfg.traffic.stuckRerouteSec) + " s，"
                    + (changed ? "已绕行" : "暂无更优路线，继续等待"));
        }
    }

    int currentCell(Agent a) {
        int c = map.cellAt(a.x, a.y);
        if (c >= 0 && map.passable(c) && !map.isCross(c)) return c;
        return nearestLaneCell(a.x, a.y);
    }

    private int nearestLaneCell(double x, double y) {
        int best = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int c : map.laneCells()) {
            if (!map.passable(c)) continue;
            double d = Math.hypot(map.centerX(c) - x, map.centerY(c) - y);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private int manhattan(int a, int b) {
        return Math.abs(map.cx(a) - map.cx(b)) + Math.abs(map.cy(a) - map.cy(b));
    }

    // =====================================================================
    // 派单
    // =====================================================================

    private void dispatch() {
        List<Agent> idle = new ArrayList<>();
        for (Agent a : agents) {
            if (a.taxi && a.status == AgentStatus.CRUISING && !a.replanPending && a.route != null) idle.add(a);
        }
        List<Task> waiting = new ArrayList<>();
        for (Task t : activeTasks) {
            if (t.state == Task.State.WAITING) waiting.add(t);
        }
        if (idle.isEmpty() || waiting.isEmpty()) return;

        long t0 = System.nanoTime();
        double res = map.resolution();
        double[][] cost = new double[idle.size()][waiting.size()];
        double[] waitSec = new double[waiting.size()];
        for (int j = 0; j < waiting.size(); j++) {
            Task t = waiting.get(j);
            waitSec[j] = time - t.createdAt;
            int[] field = DistanceField.toGoal(map, t.pickup);
            for (int i = 0; i < idle.size(); i++) {
                Agent a = idle.get(i);
                int k = Math.max(a.authority, a.progress);
                int d = field[a.route.cell(k)];
                cost[i][j] = d < 0 ? Double.POSITIVE_INFINITY : (d + (k - a.progress)) * res;
            }
        }
        List<int[]> pairs = dispatcher.assign(cost, waitSec);
        metrics.dispatchRounds++;
        for (int[] pair : pairs) {
            Agent a = idle.get(pair[0]);
            Task t = waiting.get(pair[1]);
            if (!planRoute(a, t.pickup, Purpose.PICKUP, null)) continue;
            t.state = Task.State.ASSIGNED;
            t.agentId = a.id;
            t.assignedAt = time;
            a.task = t;
            a.status = AgentStatus.TO_PICKUP;
            metrics.tasksAssigned++;
            events.info(time, "订单 " + t.id + " 派给 " + a.id + "（接驾约 " + Math.round(cost[pair[0]][pair[1]]) + " m，"
                    + dispatcher.name() + "）");
        }
        metrics.lastDispatchMs = (System.nanoTime() - t0) / 1e6;
    }

    // =====================================================================
    // 订单
    // =====================================================================

    public int waitingCount() {
        int n = 0;
        for (Task t : activeTasks) {
            if (t.state == Task.State.WAITING) n++;
        }
        return n;
    }

    boolean isPickupTaken(int cell) {
        for (Task t : activeTasks) {
            if (t.pickup == cell && t.state != Task.State.PICKED) return true;
        }
        return false;
    }

    /** 新建订单。起终点必须是可通行的车道格。 */
    public Task createTask(int pickup, int dropoff) {
        if (!map.isLane(pickup) || !map.isLane(dropoff)) throw new IllegalArgumentException("上下车点必须是车道格");
        if (!map.passable(pickup) || !map.passable(dropoff)) throw new IllegalArgumentException("上下车点被路障封闭");
        if (pickup == dropoff) throw new IllegalArgumentException("上下车点不能相同");
        Task t = new Task(String.format("T%03d", ++taskSeq), pickup, dropoff, time);
        activeTasks.add(t);
        metrics.tasksCreated++;
        events.info(time, "新订单 " + t.id + "：(" + map.cx(pickup) + "," + map.cy(pickup) + ") → ("
                + map.cx(dropoff) + "," + map.cy(dropoff) + ")");
        return t;
    }

    private void cancelTask(Task t, String reason) {
        if (t.state == Task.State.ASSIGNED && t.agentId != null) {
            Agent a = byId.get(t.agentId);
            if (a != null && a.task == t) {
                a.task = null;
                a.status = AgentStatus.CRUISING;
                startCruise(a);
            }
        }
        t.state = Task.State.CANCELLED;
        metrics.tasksCancelled++;
        activeTasks.remove(t);
        events.warn(time, "订单 " + t.id + " 已取消：" + reason);
    }

    // =====================================================================
    // 路障（动态障碍）
    // =====================================================================

    /** 设置或解除路障，并让受影响的车辆立即绕行。 */
    public void setRoadblock(int cell, boolean on) {
        if (!map.type(cell).drivable()) throw new IllegalArgumentException("建筑格不能设置路障");
        map.setBlocked(cell, on);
        events.warn(time, (on ? "设置路障 " : "解除路障 ") + "(" + map.cx(cell) + "," + map.cy(cell) + ")");
        if (!on) return;
        for (Task t : new ArrayList<>(activeTasks)) {
            if (t.pickup == cell && t.state != Task.State.PICKED) {
                cancelTask(t, "上车点被路障封闭");
            } else if (t.dropoff == cell && t.state == Task.State.PICKED) {
                t.dropoff = nearestLaneCell(map.centerX(cell) + 1.5 * map.resolution(), map.centerY(cell));
            }
        }
        for (Agent a : agents) {
            if (!a.taxi || a.route == null) continue;
            if (a.route.indexOf(cell, a.authority + 1, a.route.last()) >= 0) replanForStatus(a);
        }
    }

    // =====================================================================
    // 智能体管理
    // =====================================================================

    /**
     * 在一个空闲的车道格上新增一辆内部仿真出租车。
     *
     * @param id 指定编号（形如 car-12），为 null 时自动编号
     */
    Agent addTaxi(String id) {
        int num;
        if (id == null) {
            num = nextTaxiNum;
            id = "car-" + num;
        } else {
            num = Integer.parseInt(id.substring(id.indexOf('-') + 1));
        }
        if (byId.containsKey(id)) return byId.get(id);
        int cell = findSpawnCell();
        if (cell < 0) return null;
        nextTaxiNum = Math.max(nextTaxiNum, num + 1);
        Agent a = new Agent(id, num, true);
        a.x = map.centerX(cell);
        a.y = map.centerY(cell);
        a.theta = map.type(cell).laneDir.angle();
        a.odoX = a.x;
        a.odoY = a.y;
        register(a);
        startCruise(a);
        return a;
    }

    /** 新增自由智能体（手机等），不参与派单，作为动态障碍参与交通管控。 */
    Agent addFree(String requestedId, double x, double y, double theta) {
        String id = requestedId;
        if (id == null || id.isBlank() || byId.containsKey(id)) {
            do {
                id = "phone-" + nextFreeNum++;
            } while (byId.containsKey(id));
        }
        Agent a = new Agent(id, 100 + nextFreeNum++, false);
        a.x = x;
        a.y = y;
        a.theta = theta;
        register(a);
        return a;
    }

    void removeAgent(Agent a) {
        agents.remove(a);
        byId.remove(a.id);
        for (int i = 0; i < agents.size(); i++) agents.get(i).index = i;
    }

    private void register(Agent a) {
        a.index = agents.size();
        agents.add(a);
        byId.put(a.id, a);
    }

    private int findSpawnCell() {
        int[] lanes = map.laneCells();
        for (int tries = 0; tries < 500; tries++) {
            int c = lanes[rng.nextInt(lanes.length)];
            if (!map.passable(c)) continue;
            boolean clear = true;
            for (Agent o : agents) {
                if (Math.hypot(o.x - map.centerX(c), o.y - map.centerY(c)) < 2.5 * map.resolution()) {
                    clear = false;
                    break;
                }
                if (o.route != null && o.route.indexOf(c, o.progress, o.authority) >= 0) {
                    clear = false;
                    break;
                }
            }
            if (clear) return c;
        }
        return -1;
    }

    /** 设备接管 / 释放时切换驱动源。 */
    void setDriver(Agent a, DriverKind kind, DeviceLink link) {
        boolean wasExternal = !a.internal();
        if (kind != DriverKind.INTERNAL && !wasExternal) rebaseRoute(a);
        a.driver = kind;
        a.link = link;
        if (kind == DriverKind.INTERNAL && wasExternal && a.route != null) {
            // 外部设备离线：把车"吸附"回路线上，由内部仿真继续驾驶
            a.s = a.route.project(a.x, a.y, Math.max(0, a.progress - 1), Math.min(a.route.last(), a.authority + 1));
            a.v = 0;
            a.permitDirty = true;
        }
        a.lastSentAuthority = -1;
        a.lastSentRouteId = -1;
        a.routeDirty = true;
        a.permitDirty = true;
    }

    // =====================================================================
    // 设备消息入口（由仿真线程调用）
    // =====================================================================

    public void onDeviceMessage(DeviceLink link, JsonObject msg) {
        devices.onMessage(link, msg);
    }

    public void onDeviceClosed(DeviceLink link) {
        devices.onClosed(link);
    }

    public void onUdpMessage(JsonObject msg, String remote) {
        devices.onUdp(msg, remote);
    }

    // =====================================================================
    // 快照
    // =====================================================================

    public Snapshot snapshot(boolean paused, double timeScale) {
        List<Snapshot.AgentView> av = new ArrayList<>(agents.size());
        int external = 0, free = 0, taxis = 0;
        for (Agent a : agents) {
            if (a.taxi) {
                taxis++;
                if (!a.internal()) external++;
            } else {
                free++;
            }
            double[] route = null;
            int[] held = null;
            double[] stop = null;
            if (a.taxi && a.route != null) {
                Route r = a.route;
                int from = Math.min(a.progress, r.last());
                route = new double[(r.last() - from + 1) * 2];
                for (int i = from; i <= r.last(); i++) {
                    route[(i - from) * 2] = Num.r3(r.centerX(i));
                    route[(i - from) * 2 + 1] = Num.r3(r.centerY(i));
                }
                int hFrom = Math.min(a.progress, a.authority);
                held = new int[(a.authority - hFrom + 1) * 2];
                for (int i = hFrom; i <= a.authority; i++) {
                    held[(i - hFrom) * 2] = map.cx(r.cell(i));
                    held[(i - hFrom) * 2 + 1] = map.cy(r.cell(i));
                }
                var p = r.poseAt(Math.max(0, traffic.stopS(a)));
                stop = new double[]{Num.r3(p.x()), Num.r3(p.y())};
            }
            av.add(new Snapshot.AgentView(a.id, a.num, a.taxi, a.driver.name(), a.online(),
                    a.link != null ? a.link.remote() : null, a.status.name(),
                    Num.r3(a.x), Num.r3(a.y), Num.r3(a.theta), Num.r3(a.v), traffic.radiusOf(a),
                    a.task != null ? a.task.id : null, route, held, stop, a.routeId,
                    !Double.isNaN(a.blockedSince)));
        }
        List<Snapshot.TaskView> tv = new ArrayList<>(activeTasks.size());
        for (Task t : activeTasks) {
            tv.add(new Snapshot.TaskView(t.id, t.state.name(),
                    new int[]{map.cx(t.pickup), map.cy(t.pickup)}, new int[]{map.cx(t.dropoff), map.cy(t.dropoff)},
                    t.agentId, Num.r1(time - t.createdAt)));
        }
        int[] blockedCells = map.blockedCells();
        int[] blocked = new int[blockedCells.length * 2];
        for (int i = 0; i < blockedCells.length; i++) {
            blocked[i * 2] = map.cx(blockedCells[i]);
            blocked[i * 2 + 1] = map.cy(blockedCells[i]);
        }
        return new Snapshot("snapshot", Num.r3(time), System.currentTimeMillis(), paused, timeScale,
                dispatcher.name(), cfg.tasks.ratePerMin, av, tv,
                metrics.view(time, waitingCount(), taxis, external, free), events.recent(30), blocked);
    }
}
