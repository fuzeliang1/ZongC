package com.zongc.dw.traffic;

import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.plan.Route;
import com.zongc.dw.world.Agent;
import com.zongc.dw.world.Metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 交通管控：基于格子预约的"移动授权"（思路来自铁路的移动闭塞 / 移动授权）。
 *
 * <p>规则：</p>
 * <ol>
 *   <li>每个格子同一时刻只能属于一辆车：车身覆盖的格子 + 已获授权的前方格子都归它所有。</li>
 *   <li>车辆只能驶入已获授权的格子；授权终点之前必须停车（车身不越过边界）。</li>
 *   <li>授权每次向前申请 horizonCells 格；遇到路口时必须把整段路口格 + 出口第一格一次性申请到，
 *       否则一格也不给 ——"不堵路口"，从而避免路口内的互相卡死。</li>
 *   <li>申请顺序按"受阻时间"排序，等得最久的先申请；等待超过 priorityHoldSec 的那一辆可以
 *       先占住它需要的空闲格，防止被源源不断的车流"饿死"。</li>
 *   <li>受阻超过 stuckRerouteSec 的车交给 World 绕行重规划。</li>
 *   <li>授权一般只增不减；例外是已授权的格子变得不可通行（路障、感知发现的障碍），
 *       这时立即收回授权（{@link #revokeBefore}）。</li>
 * </ol>
 */
public final class TrafficController {

    /** 一次更新的结果：需要 World 处理的车辆。 */
    public record Result(List<Agent> stuck, List<Integer> stuckBlockingCells, List<Agent> offRoute) {}

    /**
     * 授权被收回后，外部设备要等下一条授权到达才知道新的停车点：最多差一个上报周期（10 Hz）
     * + 一个仿真步 + 网络往返 + 设备控制周期，再加上刹车。这段时间里设备在原先已授权的范围内
     * 多走进一格不算违规（记入 Metrics#revokeOverruns），超过这个时间仍越界才算设备不遵守授权。
     */
    public static final double REVOKE_GRACE_SEC = 1.0;

    private final GridMap map;
    private final Config cfg;
    private final int[] owner;
    /** 最近一次 update 的仿真时间（revokeBefore 也会在两次 update 之间被 World 调用）。 */
    private double now;

    public TrafficController(GridMap map, Config cfg) {
        this.map = map;
        this.cfg = cfg;
        this.owner = new int[map.size()];
    }

    /** 格子当前归属的智能体下标，-1 表示空闲。 */
    public int ownerOf(int cell) {
        return owner[cell];
    }

    /** 内部仿真车应当停下的弧长位置（授权终点前留出车身半径和余量）。 */
    public double stopS(Agent a) {
        Route r = a.route;
        if (r == null) return a.s;
        if (a.authority >= r.last()) return r.length();
        return r.sEnd(a.authority) - (cfg.vehicle.bodyRadius + cfg.vehicle.stopMargin);
    }

    /** 外部设备使用的停车位置：沿设备路点折线的累计长度（见 Route#devicePolyline）。 */
    public double stopPolylineS(Agent a) {
        Route r = a.route;
        if (r == null) return 0;
        return r.chordAt(Math.max(0, stopS(a)));
    }

    public Result update(List<Agent> agents, double time, double dt, Metrics metrics) {
        now = time;
        List<Agent> offRoute = new ArrayList<>();

        // 1. 车身覆盖的格子 & 路线进度
        for (Agent a : agents) {
            a.bodyCells = map.cellsOverlappingCircle(a.x, a.y, cfg.vehicle.bodyRadius);
            if (a.route == null) continue;
            if (a.internal()) {
                a.progress = a.route.pieceAt(a.s);
            } else if (updateExternalProgress(a, dt, metrics)) {
                offRoute.add(a);
            }
        }

        // 2. 重建占用表：先车身，再已有授权。已授权的格子变得不可通行时先把授权收回
        Arrays.fill(owner, -1);
        for (Agent a : agents) {
            for (int c : a.bodyCells) {
                if (owner[c] < 0) owner[c] = a.index();
            }
        }
        for (Agent a : agents) {
            if (a.route == null) continue;
            enforceAuthority(a);
            for (int i = a.progress; i <= a.authority; i++) {
                int c = a.route.cell(i);
                if (owner[c] < 0) owner[c] = a.index();
            }
        }

        // 3. 按受阻时间排序后依次申请授权
        List<Agent> order = new ArrayList<>();
        for (Agent a : agents) {
            if (a.route != null) order.add(a);
        }
        order.sort(Comparator.comparingDouble((Agent a) -> Double.isNaN(a.blockedSince) ? Double.MAX_VALUE : a.blockedSince)
                .thenComparingInt(a -> a.num));

        boolean holdUsed = false;
        List<Agent> stuck = new ArrayList<>();
        List<Integer> stuckCells = new ArrayList<>();
        for (Agent a : order) {
            Route r = a.route;
            int target = Math.min(r.last(), a.progress + cfg.traffic.horizonCells);
            if (a.authority >= target) {
                a.blockedSince = Double.NaN;
                continue;
            }
            int before = a.authority;
            int[] pending = extend(a, target);
            if (a.authority != before) a.permitDirty = true;
            if (pending == null) {
                a.blockedSince = Double.NaN;
                continue;
            }
            // 申请失败：车已经停下或快到停车点时，才算真正"受阻"
            boolean waiting = a.v < 0.05 || (a.internal() && stopS(a) - a.s < 0.5 * map.resolution());
            if (waiting && Double.isNaN(a.blockedSince)) a.blockedSince = time;
            if (Double.isNaN(a.blockedSince)) continue;

            double waited = time - a.blockedSince;
            if (!holdUsed && waited > cfg.traffic.priorityHoldSec) {
                holdUsed = true;
                for (int i = pending[0]; i <= pending[1]; i++) {
                    int c = r.cell(i);
                    if (owner[c] < 0 && map.passable(c)) owner[c] = a.index();
                }
            }
            if (waited > cfg.traffic.stuckRerouteSec && time - a.lastRerouteAt > cfg.traffic.stuckRerouteSec) {
                stuck.add(a);
                stuckCells.add(r.cell(pending[0]));
            }
        }
        return new Result(stuck, stuckCells, offRoute);
    }

    /**
     * 已获授权、但车还没开到的格子变得不可通行（人工路障、车辆感知发现的障碍）时，立即把授权收回到它之前。
     * 内部仿真车会按"授权终点前刹停"的约束马上减速 / 停住，外部设备会收到新的停车点。
     */
    private void enforceAuthority(Agent a) {
        Route r = a.route;
        for (int i = a.progress + 1; i <= a.authority; i++) {
            if (map.passable(r.cell(i))) continue;
            revokeBefore(a, i);
            return;
        }
    }

    /**
     * 收回授权：路线第 idx 格及之后的格子不再允许进入。
     * 如果新的授权终点落在路口里、而车还没进路口，就退到路口之前，不让车停在路口中间。
     *
     * @return 授权是否发生了变化
     */
    public boolean revokeBefore(Agent a, int idx) {
        Route r = a.route;
        if (r == null || idx > a.authority) return false;
        int k = Math.max(a.progress, idx - 1);
        if (!map.isCross(r.cell(a.progress))) {
            while (k > a.progress && map.isCross(r.cell(k))) k--;
        }
        if (k >= a.authority) return false;
        boolean recent = a.revokedRoute == r && now - a.revokedAt <= REVOKE_GRACE_SEC;
        a.authorityBeforeRevoke = recent ? Math.max(a.authorityBeforeRevoke, a.authority) : a.authority;
        a.revokedAt = now;
        a.revokedRoute = r;
        a.authority = k;
        a.permitDirty = true;
        return true;
    }

    /**
     * 尝试把授权延伸到 target。
     *
     * @return null 表示没有遇到阻挡；否则返回申请失败的路线下标区间 {from, to}
     */
    private int[] extend(Agent a, int target) {
        Route r = a.route;
        int k = a.authority;
        int last = r.last();
        while (k < target) {
            int next = k + 1;
            if (map.isCross(r.cell(next))) {
                int j = next;
                while (j <= last && map.isCross(r.cell(j))) j++;
                int end = Math.min(j, last);   // 整段路口 + 出口第一格
                if (!allFree(r, next, end, a)) {
                    a.authority = k;
                    return new int[]{next, end};
                }
                claim(r, next, end, a);
                k = end;
            } else {
                if (!free(r.cell(next), a)) {
                    a.authority = k;
                    return new int[]{next, next};
                }
                claim(r, next, next, a);
                k = next;
            }
        }
        a.authority = k;
        return null;
    }

    private boolean free(int cell, Agent a) {
        return map.passable(cell) && (owner[cell] < 0 || owner[cell] == a.index());
    }

    private boolean allFree(Route r, int from, int to, Agent a) {
        for (int i = from; i <= to; i++) {
            if (!free(r.cell(i), a)) return false;
        }
        return true;
    }

    private void claim(Route r, int from, int to, Agent a) {
        for (int i = from; i <= to; i++) owner[r.cell(i)] = a.index();
    }

    /**
     * 根据外部设备上报的位置更新路线进度。
     *
     * @return true 表示车辆偏离路线过久，需要从当前位置重新规划
     */
    private boolean updateExternalProgress(Agent a, double dt, Metrics metrics) {
        Route r = a.route;
        int cell = map.cellAt(a.x, a.y);
        int found = cell < 0 ? -1 : r.indexOf(cell, a.progress, r.last());
        if (found >= 0) {
            a.progress = found;
            a.offRouteTime = 0;
            if (a.progress > a.authority) {
                // 设备越过了授权终点。如果授权刚被收回（REVOKE_GRACE_SEC 内）、设备只是在收回前已授权的范围内
                // 多走进了一格，说明它还没收到新停车点，记为 revokeOverruns；否则是设备不遵守授权，记一次违规。
                // 两种情况都承认既成事实（车身所在格已被占用）
                boolean late = a.revokedRoute == r && now - a.revokedAt <= REVOKE_GRACE_SEC
                        && a.progress <= Math.min(a.authorityBeforeRevoke, a.authority + 1);
                if (late) metrics.revokeOverruns++;
                else metrics.authorityViolations++;
                a.authority = a.progress;
                a.permitDirty = true;
            }
            return false;
        }
        boolean nearPrevious = a.progress > 0 && cell == r.cell(a.progress - 1);
        if (!nearPrevious) a.offRouteTime += dt;
        return a.offRouteTime > 3.0;
    }
}
