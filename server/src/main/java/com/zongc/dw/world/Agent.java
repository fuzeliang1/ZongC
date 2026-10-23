package com.zongc.dw.world;

import com.zongc.dw.plan.Route;

/**
 * 数字世界中的一个自主运动体（AMO）。
 *
 * <p>出租车（taxi=true）按服务端下发的路线行驶、参与派单；
 * 自由智能体（taxi=false，例如手机模拟车）只上报位置，作为动态障碍参与交通管控。</p>
 *
 * <p>为了让仿真代码简洁，字段直接公开；只有仿真线程会修改它们。</p>
 */
public final class Agent {
    public final String id;
    public final int num;
    public final boolean taxi;
    /** 在 World 智能体列表中的下标。 */
    int index;

    // ---------- 驱动源（数字孪生） ----------
    public DriverKind driver = DriverKind.INTERNAL;
    public DeviceLink link;
    public long lastSeenNanos;
    public long stateCount;

    // ---------- 位姿（米、弧度、m/s、rad/s） ----------
    public double x, y, theta, v, omega;

    // ---------- 任务 ----------
    public AgentStatus status;
    public Task task;
    public double dwell;

    // ---------- 路线与行驶授权 ----------
    public Route route;
    public int routeId;
    public Purpose purpose = Purpose.CRUISE;
    public int goal = -1;
    /** 车辆中心当前所在的路线格下标。 */
    public int progress;
    /** 已获授权可以驶入的最远路线格下标（移动授权终点）。 */
    public int authority;
    /** 内部仿真车在路线上的弧长位置。 */
    public double s;
    /** 车身覆盖的格子。 */
    public int[] bodyCells = new int[0];
    public double blockedSince = Double.NaN;
    public double lastRerouteAt = -1e9;
    public double offRouteTime;
    public boolean replanPending;
    public double replanRetryAt;
    /** 需要向外部设备重新下发路线 / 授权。 */
    public boolean routeDirty, permitDirty;
    public int lastSentAuthority = -1;
    public int lastSentRouteId = -1;

    /** 空车调度给它分配的区域（在该区域内就近巡游），-1 表示没有。 */
    public int homeZone = -1;

    // ---------- 统计 ----------
    public double distEmpty, distLoaded;
    public int delivered;
    double odoX, odoY;

    public Agent(String id, int num, boolean taxi) {
        this.id = id;
        this.num = num;
        this.taxi = taxi;
        this.status = taxi ? AgentStatus.CRUISING : AgentStatus.FREE;
    }

    public boolean internal() {
        return driver == DriverKind.INTERNAL;
    }

    public boolean online() {
        return internal() || link != null;
    }

    public int index() {
        return index;
    }
}
