package com.zongc.phone.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 数字世界快照中手机需要的部分（GET /api/snapshot）：所有智能体、订单、路障。 */
public final class WorldSnapshot {
    /** 一个智能体（出租车或手机）。 */
    public static final class Agent {
        public String id;
        public int num;
        public boolean taxi;
        public String driver;
        public String status;
        public boolean online = true;
        public double x;
        public double y;
        public double theta;
        public double v;
        public double r;
        public String taskId;
        /** 规划路线上各格中心点，扁平数组 [x0, y0, x1, y1, ...]；可能为 null。 */
        public double[] route;
        public boolean blocked;
    }

    /** 一个订单。 */
    public static final class Task {
        public String id;
        /** WAITING / ASSIGNED / PICKED / DELIVERED / CANCELLED。 */
        public String state;
        public int fromX;
        public int fromY;
        public int toX;
        public int toY;
        public String agent;
        public double age;
    }

    public double t;
    public boolean paused;
    public final List<Agent> agents = new ArrayList<Agent>();
    public final List<Task> tasks = new ArrayList<Task>();
    /** 路障格，扁平数组 [x0, y0, x1, y1, ...]。 */
    public int[] blocked = new int[0];

    public static WorldSnapshot fromJson(String json) {
        Map<String, Object> o = Json.parseObject(json);
        if (o == null) throw new IllegalArgumentException("快照不是 JSON 对象");
        WorldSnapshot s = new WorldSnapshot();
        s.t = Json.num(o, "t", 0);
        s.paused = Json.bool(o, "paused", false);
        List<Object> agents = Json.arr(o.get("agents"));
        if (agents != null) {
            for (Object item : agents) {
                Map<String, Object> m = Json.obj(item);
                if (m == null || Json.str(m, "id") == null) continue;
                Agent a = new Agent();
                a.id = Json.str(m, "id");
                a.num = (int) Json.num(m, "num", 0);
                a.taxi = Json.bool(m, "taxi", false);
                a.driver = Json.str(m, "driver");
                a.status = Json.str(m, "status");
                a.online = Json.bool(m, "online", true);
                a.x = Json.num(m, "x", 0);
                a.y = Json.num(m, "y", 0);
                a.theta = Json.num(m, "theta", 0);
                a.v = Json.num(m, "v", 0);
                a.r = Json.num(m, "r", a.taxi ? 0.3 : 0.45);
                a.taskId = Json.str(m, "taskId");
                a.route = Json.nums(m, "route");
                a.blocked = Json.bool(m, "blocked", false);
                s.agents.add(a);
            }
        }
        List<Object> tasks = Json.arr(o.get("tasks"));
        if (tasks != null) {
            for (Object item : tasks) {
                Map<String, Object> m = Json.obj(item);
                if (m == null) continue;
                double[] from = Json.nums(m, "from");
                double[] to = Json.nums(m, "to");
                if (from == null || to == null || from.length < 2 || to.length < 2) continue;
                Task t = new Task();
                t.id = Json.str(m, "id");
                t.state = Json.str(m, "state");
                t.fromX = (int) from[0];
                t.fromY = (int) from[1];
                t.toX = (int) to[0];
                t.toY = (int) to[1];
                t.agent = Json.str(m, "agent");
                t.age = Json.num(m, "age", 0);
                s.tasks.add(t);
            }
        }
        double[] b = Json.nums(o, "blocked");
        if (b != null) {
            s.blocked = new int[b.length - (b.length % 2)];
            for (int i = 0; i < s.blocked.length; i++) s.blocked[i] = (int) b[i];
        }
        return s;
    }

    public Agent agent(String id) {
        if (id == null) return null;
        for (Agent a : agents) if (id.equals(a.id)) return a;
        return null;
    }

    public Task task(String id) {
        if (id == null) return null;
        for (Task t : tasks) if (id.equals(t.id)) return t;
        return null;
    }

    public int taxiCount() {
        int n = 0;
        for (Agent a : agents) if (a.taxi) n++;
        return n;
    }
}
