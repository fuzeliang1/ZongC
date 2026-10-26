package com.zongc.phone.tools;

import com.zongc.phone.core.DwConnection;
import com.zongc.phone.core.MapClient;
import com.zongc.phone.core.MapData;
import com.zongc.phone.core.PdrConfig;
import com.zongc.phone.core.PdrEngine;
import com.zongc.phone.core.PdrState;
import com.zongc.phone.core.RouteFollower;
import com.zongc.phone.core.SyntheticWalker;
import com.zongc.phone.core.WorldSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 在电脑上模拟一部"拿在手上走路的手机"，接入正在运行的数字世界服务端做端到端检查。
 *
 * <p>与 App 用的是同一套核心代码（PdrEngine + RouteFollower + DwConnection + MapClient），只是传感器数据
 * 来自 {@link SyntheticWalker}（按真实时间节奏产生）。检查项：</p>
 * <ol>
 *   <li>手机绑定一辆数字车（driver = PHONE），不新增智能体；</li>
 *   <li>人站着不动时车不动；</li>
 *   <li>人走路时车沿服务端的路线前进，开过的路程不超过"走过的路程 × 驾驶比例"；
 *       服务端快照里的位置跟得上手机本地（10 Hz 上报）；</li>
 *   <li>人停下后车停下；</li>
 *   <li>断开后这辆车留在数字世界里，由内部仿真接管（driver = INTERNAL）。</li>
 * </ol>
 * 用法：先启动服务端（scripts/start_server），然后
 * <pre>  java -cp ... com.zongc.phone.tools.PhoneSim [--host 127.0.0.1] [--seconds 30] [--id car-6] [--scale 1]</pre>
 */
public final class PhoneSim {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int tcp = 9000, http = 8080;
        String id = "car-6";
        double seconds = 30, scale = 1;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--host")) host = args[i + 1];
            else if (args[i].equals("--tcp")) tcp = Integer.parseInt(args[i + 1]);
            else if (args[i].equals("--http")) http = Integer.parseInt(args[i + 1]);
            else if (args[i].equals("--id")) id = args[i + 1];
            else if (args[i].equals("--seconds")) seconds = Double.parseDouble(args[i + 1]);
            else if (args[i].equals("--scale")) scale = Double.parseDouble(args[i + 1]);
        }
        boolean ok = new PhoneSim(host, tcp, http, scale).run(id, Math.max(15, seconds));
        System.out.println(ok ? "PhoneSim: 全部检查通过" : "PhoneSim: 有检查未通过");
        System.exit(ok ? 0 : 1);
    }

    private final String host;
    private final int tcp;
    private final MapClient api;
    private final double driveScale;
    private final PdrEngine engine = new PdrEngine(new PdrConfig());
    private final RouteFollower car = new RouteFollower();
    private final SyntheticWalker walker = new SyntheticWalker(42);
    private final SyntheticWalker.Sink sink = SyntheticWalker.into(engine);
    private final AtomicLong now = new AtomicLong();
    private long wall0;
    private long sim0;

    // 运行中的统计（主线程写、读）
    private String me;
    private double carDist;
    private double lastX = Double.NaN;
    private double lastY;
    private double maxErr;
    private double sumErr;
    private int polls;
    private int seen;
    private boolean driverOk = true;
    private final List<String> statuses = new ArrayList<String>();

    private PhoneSim(String host, int tcp, int http, double driveScale) {
        this.host = host;
        this.tcp = tcp;
        this.api = new MapClient(host, http);
        this.driveScale = driveScale;
    }

    private boolean run(String wantedId, double seconds) throws Exception {
        MapData map = api.fetchMap();
        System.out.printf("地图 %s：%d×%d 格，%.1f m/格；驾驶比例 %.2f（人走 1 m，车开 %.2f m）%n",
                map.name, map.width, map.height, map.resolution, driveScale, driveScale);
        WorldSnapshot before = api.fetchSnapshot();

        walker.stand(1.5, 0, sink);
        now.set(walker.t);
        wall0 = System.nanoTime();
        sim0 = walker.t;
        DwConnection conn = new DwConnection(host, tcp, wantedId, "PhoneSim", new DwConnection.PoseSource() {
            @Override
            public double[] pose() {
                long t = now.get();
                return car.step(t, engine.state(t).v * driveScale);
            }
        }, new DwConnection.Listener() {
            @Override
            public void onMessage(Map<String, Object> m) {
                car.onMessage(m);
            }

            @Override
            public void onDisconnected() {
                car.reset();
            }
        }, 10);
        conn.start();
        long deadline = System.currentTimeMillis() + 5000;
        while ((conn.status().state != DwConnection.State.CONNECTED || !car.status().ready)
                && System.currentTimeMillis() < deadline) {
            advance(0.1, false);
        }
        DwConnection.Status st = conn.status();
        if (st.state != DwConnection.State.CONNECTED) {
            System.out.println("✗ 连接失败：" + st.error);
            conn.stop();
            return false;
        }
        me = st.agentId;
        advance(1.0, false);                                // 等服务端处理第一批 state
        WorldSnapshot snap = api.fetchSnapshot();
        WorldSnapshot.Agent a = snap.agent(me);
        int expected = before.agents.size() + (before.agent(me) == null ? 1 : 0);
        boolean pass = true;
        if (a == null || !"PHONE".equals(a.driver) || snap.agents.size() != expected) {
            System.out.printf("✗ 绑定不对：%s driver=%s，车辆数 %d（应为 %d）%n",
                    me, a == null ? "无" : a.driver, snap.agents.size(), expected);
            pass = false;
        } else {
            System.out.printf("✓ 已绑定数字车 %s（driver=PHONE），车辆总数 %d 不变%n", me, snap.agents.size());
        }

        // ---- 站着不动：车不动
        double[] p0 = {a == null ? Double.NaN : a.x, a == null ? Double.NaN : a.y};
        advance(3.0, false);
        WorldSnapshot.Agent a1 = api.fetchSnapshot().agent(me);
        double still = a1 == null ? Double.NaN : Math.hypot(a1.x - p0[0], a1.y - p0[1]);
        if (!(still < 0.1)) {
            System.out.printf("✗ 人站着不动时车移动了 %.2f m%n", still);
            pass = false;
        } else {
            System.out.println("✓ 人站着不动，车停在原地");
        }

        // ---- 走路：车沿路线前进
        PdrState w0 = engine.state(now.get());
        carDist = 0;
        lastX = Double.NaN;
        advance(0.7 * seconds, true);
        PdrState w1 = engine.state(now.get());
        double walked = w1.distance - w0.distance;
        System.out.printf("走了 %d 步（模拟器实际 %d 步），%.1f m；车沿路线开了 %.1f m%n",
                w1.steps - w0.steps, walker.trueSteps, walked, carDist);
        System.out.println("  途中车的状态：" + statuses);
        if (seen == 0 || !driverOk) {
            System.out.println("✗ 服务端快照里没有看到 " + me + "（或驱动方式不是 PHONE）");
            pass = false;
        } else {
            System.out.printf("✓ 服务端看到 %s %d/%d 次，与手机本地位置差 平均 %.2f m、最大 %.2f m%n",
                    me, seen, polls, sumErr / seen, maxErr);
            if (maxErr > 0.8) {
                System.out.println("✗ 位置差过大");
                pass = false;
            }
        }
        if (Math.abs((w1.steps - w0.steps) - walker.trueSteps) > 2) {
            System.out.println("✗ 步数偏差过大");
            pass = false;
        }
        if (carDist < 2.0) {
            System.out.println("✗ 人走了一路，车几乎没动");
            pass = false;
        } else if (carDist > walked * driveScale * 1.1 + 1.5) {
            System.out.println("✗ 车开得比人走得还远（应以走路为油门）");
            pass = false;
        } else {
            System.out.printf("✓ 走路就是油门：车开了 %.1f m ≤ 走了 %.1f m × %.2f%n", carDist, walked, driveScale);
        }

        // ---- 停下：车停下
        advance(Math.max(4, 0.3 * seconds), false);
        RouteFollower.Status local = car.status();
        WorldSnapshot.Agent a2 = api.fetchSnapshot().agent(me);
        if (local.v != 0 || a2 == null || a2.v > 0.05) {
            System.out.printf("✗ 人停下后车没有停（本地 %.2f m/s，服务端 %s）%n", local.v,
                    a2 == null ? "无" : String.format("%.2f m/s", a2.v));
            pass = false;
        } else {
            System.out.println("✓ 人停下，车也停下");
        }

        // ---- 断开：车留在数字世界，由内部仿真接管
        conn.stop();
        boolean takenOver = false;
        int count = -1;
        deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline && !takenOver) {
            Thread.sleep(100);
            WorldSnapshot s = api.fetchSnapshot();
            WorldSnapshot.Agent c = s.agent(me);
            count = s.agents.size();
            takenOver = c != null && "INTERNAL".equals(c.driver);
        }
        System.out.println(takenOver ? "✓ 断开后 " + me + " 由数字世界接管（车辆总数 " + count + "）"
                : "✗ 断开 4 s 后 " + me + " 没有被内部仿真接管");
        return pass && takenOver;
    }

    /** 按真实时间节奏让模拟的人走路或站着 seconds 秒，每 0.1 s 记一次车走过的路程，每 0.5 s 对比一次服务端快照。 */
    private void advance(double seconds, boolean walking) throws Exception {
        int n = (int) Math.round(seconds * walker.sampleHz);
        long nextPoll = 0;
        for (int i = 0; i < n; i++) {
            if (walking) walker.walk(1 / walker.sampleHz, 0, sink);
            else walker.stand(1 / walker.sampleHz, 0, sink);
            now.set(walker.t);
            long ahead = (walker.t - sim0) - (System.nanoTime() - wall0);
            if (ahead > 0) LockSupport.parkNanos(ahead);
            if (i % 5 == 0) {
                RouteFollower.Status cs = car.status();
                if (cs.ready) {
                    if (!Double.isNaN(lastX)) carDist += Math.hypot(cs.x - lastX, cs.y - lastY);
                    lastX = cs.x;
                    lastY = cs.y;
                    String label = cs.carStatus == null ? "无路线" : cs.carStatus;
                    if (statuses.isEmpty() || !statuses.get(statuses.size() - 1).equals(label)) statuses.add(label);
                }
            }
            if (me != null && walking && System.currentTimeMillis() >= nextPoll) {
                nextPoll = System.currentTimeMillis() + 500;
                WorldSnapshot.Agent a = api.fetchSnapshot().agent(me);
                RouteFollower.Status cs = car.status();
                polls++;
                if (a == null) continue;
                seen++;
                driverOk &= "PHONE".equals(a.driver);
                double err = Math.hypot(a.x - cs.x, a.y - cs.y);
                maxErr = Math.max(maxErr, err);
                sumErr += err;
                if (polls % 4 == 0) {
                    System.out.printf("  t=%5.1fs 车 (%6.2f, %5.2f) %.2f m/s，服务端 (%6.2f, %5.2f)，差 %.2f m，剩余 %.1f m%s%n",
                            (walker.t - sim0) / 1e9, cs.x, cs.y, cs.v, a.x, a.y, err, cs.remaining(),
                            cs.waitingPermit() ? "（等前方让路）" : "");
                }
            }
        }
    }
}
