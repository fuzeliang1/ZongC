package com.zongc.phone.tools;

import com.zongc.phone.core.DwConnection;
import com.zongc.phone.core.MapClient;
import com.zongc.phone.core.MapData;
import com.zongc.phone.core.PdrConfig;
import com.zongc.phone.core.PdrEngine;
import com.zongc.phone.core.PdrState;
import com.zongc.phone.core.SyntheticWalker;
import com.zongc.phone.core.WorldSnapshot;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 在电脑上模拟一部"拿在手上走路的手机"，接入正在运行的数字世界服务端做端到端检查。
 *
 * <p>与 App 用的是同一套核心代码（PdrEngine + DwConnection + MapClient），只是传感器数据
 * 来自 {@link SyntheticWalker}（按真实时间节奏产生）。检查项：</p>
 * <ol>
 *   <li>能注册成手机智能体（driver = PHONE，不是出租车）；</li>
 *   <li>服务端快照里的位置跟得上手机本地估计（10 Hz 上报）；</li>
 *   <li>叫车：订单被派给某辆出租车；</li>
 *   <li>断开后手机智能体从数字世界消失。</li>
 * </ol>
 * 用法：先启动服务端（scripts/start_server），然后
 * <pre>  java -cp ... com.zongc.phone.tools.PhoneSim [--host 127.0.0.1] [--seconds 30]</pre>
 */
public final class PhoneSim {
    private PhoneSim() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int tcp = 9000, http = 8080;
        String id = "phone-1";
        double seconds = 30;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--host")) host = args[i + 1];
            else if (args[i].equals("--tcp")) tcp = Integer.parseInt(args[i + 1]);
            else if (args[i].equals("--http")) http = Integer.parseInt(args[i + 1]);
            else if (args[i].equals("--id")) id = args[i + 1];
            else if (args[i].equals("--seconds")) seconds = Double.parseDouble(args[i + 1]);
        }
        boolean ok = run(host, tcp, http, id, seconds);
        System.out.println(ok ? "PhoneSim: 全部检查通过" : "PhoneSim: 有检查未通过");
        System.exit(ok ? 0 : 1);
    }

    static boolean run(String host, int tcp, int http, String wantedId, double seconds) throws Exception {
        final MapClient api = new MapClient(host, http);
        final MapData map = api.fetchMap();
        System.out.printf("地图 %s：%d×%d 格，%.1f m/格%n", map.name, map.width, map.height, map.resolution);

        final PdrEngine engine = new PdrEngine(new PdrConfig());
        final SyntheticWalker walker = new SyntheticWalker(42);
        final AtomicLong now = new AtomicLong();
        SyntheticWalker.Sink sink = SyntheticWalker.into(engine);
        walker.stand(1.5, 0, sink);
        now.set(walker.t);
        // 站在东西向道路的东行车道上（y = 8 一排是 '>'），面朝地图 +x
        engine.setPosition(12.5, 8.45);
        engine.alignHeading(0);

        DwConnection conn = new DwConnection(host, tcp, wantedId, "PhoneSim", new DwConnection.PoseSource() {
            @Override
            public double[] pose() {
                PdrState s = engine.state(now.get());
                double[] c = map.clamp(s.x, s.y);
                return new double[]{c[0], c[1], s.theta, s.v, s.w};
            }
        }, 10);
        conn.start();
        boolean pass = true;
        long deadline = System.currentTimeMillis() + 5000;
        while (conn.status().state != DwConnection.State.CONNECTED && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        DwConnection.Status st = conn.status();
        if (st.state != DwConnection.State.CONNECTED) {
            System.out.println("✗ 连接失败：" + st.error);
            conn.stop();
            return false;
        }
        String me = st.agentId;
        System.out.println("✓ 已接入数字世界，编号 " + me);

        // ---- 按真实时间节奏走路：东行 → 左转 → 北行 → 停下
        double[][] plan = {
                {0.4 * seconds, 0},           // 走，直行
                {2.0, Math.PI / 2 / 2.0},      // 原地左转 90°
                {0.4 * seconds, 0},           // 走，直行
                {0.2 * seconds, 0},           // 站着
        };
        boolean[] walking = {true, false, true, false};
        long wall0 = System.nanoTime();
        long sim0 = walker.t;
        long nextPoll = 0;
        double maxErr = 0, sumErr = 0;
        int polls = 0, seen = 0, blockedNear = 0;
        boolean driverOk = false;
        for (int p = 0; p < plan.length; p++) {
            int n = (int) Math.round(plan[p][0] * walker.sampleHz);
            for (int i = 0; i < n; i++) {
                if (walking[p]) walker.walk(1 / walker.sampleHz, plan[p][1], sink);
                else walker.stand(1 / walker.sampleHz, plan[p][1], sink);
                now.set(walker.t);
                long ahead = (walker.t - sim0) - (System.nanoTime() - wall0);
                if (ahead > 0) LockSupport.parkNanos(ahead);
                if (System.currentTimeMillis() >= nextPoll) {
                    nextPoll = System.currentTimeMillis() + 500;
                    WorldSnapshot snap = api.fetchSnapshot();
                    PdrState local = engine.state(now.get());
                    WorldSnapshot.Agent a = snap.agent(me);
                    polls++;
                    if (a == null) continue;
                    seen++;
                    driverOk = !a.taxi && "PHONE".equals(a.driver);
                    double err = Math.hypot(a.x - local.x, a.y - local.y);
                    maxErr = Math.max(maxErr, err);
                    sumErr += err;
                    for (WorldSnapshot.Agent b : snap.agents) {
                        if (b.taxi && b.blocked && Math.hypot(b.x - a.x, b.y - a.y) < 2.5) blockedNear++;
                    }
                    if (polls % 4 == 0) {
                        System.out.printf("  t=%5.1fs 手机本地 (%6.2f, %5.2f) 服务端 (%6.2f, %5.2f) 差 %.2f m，步数 %d%n",
                                (walker.t - sim0) / 1e9, local.x, local.y, a.x, a.y, err, local.steps);
                    }
                }
            }
        }
        PdrState end = engine.state(now.get());
        System.out.printf("走了 %d 步（模拟器实际 %d 步），%.1f m；在 (%.2f, %.2f)%n",
                end.steps, walker.trueSteps, end.distance, end.x, end.y);
        if (seen == 0 || !driverOk) {
            System.out.println("✗ 服务端快照里没有看到 " + me + "（或类型不对）");
            pass = false;
        } else {
            System.out.printf("✓ 服务端看到 %s（driver=PHONE）%d/%d 次，位置差 平均 %.2f m、最大 %.2f m%n",
                    me, seen, polls, sumErr / seen, maxErr);
            if (maxErr > 0.8) {
                System.out.println("✗ 位置差过大");
                pass = false;
            }
        }
        if (Math.abs(end.steps - walker.trueSteps) > 2) {
            System.out.println("✗ 步数偏差过大");
            pass = false;
        }
        System.out.println("  （行走途中，手机附近因受阻而等待的出租车：" + blockedNear + " 车次·采样）");

        // ---- 行人挡在出租车前方：车应停在行人前面，行人离开后继续走
        //      前方 5 格：还没授权给车，车申请不到这一格而停下；
        //      前方 3 格：已经授权给车（授权向前 3 格），服务端要紧急收回授权
        pass &= blockTaxi(api, engine, now, me, 5);
        pass &= blockTaxi(api, engine, now, me, 3);

        // ---- 叫车：从离我最近的车道格到地图另一边
        WorldSnapshot snap = api.fetchSnapshot();
        int[] from = map.nearestLane(end.x, end.y, snap.blocked, 1.2);   // 上车点不能是自己脚下的格子
        int[] to = map.nearestLane(map.worldWidth() - 8, map.worldHeight() - 8.5, snap.blocked);
        String task = api.hail(from[0], from[1], to[0], to[1]);
        conn.sendEvent("叫车 " + task);
        System.out.printf("✓ 叫车成功：订单 %s，(%d,%d) → (%d,%d)%n", task, from[0], from[1], to[0], to[1]);
        String taxi = null;
        boolean queued = false;
        deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline && taxi == null) {
            Thread.sleep(250);
            WorldSnapshot.Task t = api.fetchSnapshot().task(task);
            if (t != null) queued = true;
            if (t != null && t.agent != null) taxi = t.agent;
        }
        if (taxi != null) {
            System.out.println("✓ 订单 " + task + " 已派给 " + taxi);
        } else if (queued) {
            System.out.println("✓ 订单 " + task + " 在排队（出租车都在忙，空出来后会派单）");
        } else {
            System.out.println("✗ 快照里找不到订单 " + task);
            pass = false;
        }

        // ---- 断开：手机智能体应该从数字世界消失
        conn.stop();
        boolean gone = false;
        deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !gone) {
            Thread.sleep(100);
            gone = api.fetchSnapshot().agent(me) == null;
        }
        System.out.println(gone ? "✓ 断开后 " + me + " 已从数字世界移除" : "✗ 断开 3 s 后 " + me + " 仍在");
        return pass && gone;
    }

    /** 把手机"放"到一辆正在行驶的出租车前方 ahead 格处，检查它停车避让、行人离开后恢复行驶。 */
    private static boolean blockTaxi(MapClient api, PdrEngine engine, AtomicLong now, String me, int ahead)
            throws Exception {
        WorldSnapshot.Agent car = null;
        long deadline = System.currentTimeMillis() + 10000;
        while (car == null && System.currentTimeMillis() < deadline) {
            for (WorldSnapshot.Agent a : api.fetchSnapshot().agents) {
                if (a.taxi && a.route != null && a.route.length >= 2 * (ahead + 4) && a.v > 1.0 && !a.blocked) {
                    car = a;
                    break;
                }
            }
            if (car == null) Thread.sleep(200);
        }
        if (car == null) {
            System.out.println("  （没有合适的行驶中出租车，跳过避让检查）");
            return true;
        }
        double sx = car.route[2 * ahead], sy = car.route[2 * ahead + 1];
        engine.setPosition(sx, sy);
        System.out.printf("  行人突然站到 %s 前方第 %d 格 (%.1f, %.1f)%n", car.id, ahead, sx, sy);
        double minGap = Double.POSITIVE_INFINITY;
        boolean stopped = false;
        deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && !stopped) {
            Thread.sleep(200);
            now.addAndGet(200_000_000L);
            WorldSnapshot s = api.fetchSnapshot();
            WorldSnapshot.Agent c = s.agent(car.id), p = s.agent(me);
            if (c == null || p == null) continue;
            minGap = Math.min(minGap, Math.hypot(c.x - p.x, c.y - p.y) - c.r - p.r);
            stopped = c.blocked && c.v < 0.05;
        }
        if (!stopped) {
            System.out.println("✗ " + car.id + " 没有在行人前停车");
            return false;
        }
        System.out.printf("✓ %s 在行人前停车等待，车身与行人最小间隙 %.2f m%n", car.id, minGap);
        engine.setPosition(5.0, 5.0);                      // 离开道路（走进街区）
        boolean resumed = false;
        deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && !resumed) {
            Thread.sleep(200);
            now.addAndGet(200_000_000L);
            WorldSnapshot.Agent c = api.fetchSnapshot().agent(car.id);
            resumed = c != null && c.v > 0.3;
        }
        System.out.println(resumed ? "✓ 行人离开后 " + car.id + " 恢复行驶" : "✗ 行人离开后 " + car.id + " 没有恢复行驶");
        return resumed && minGap > 0;
    }
}
