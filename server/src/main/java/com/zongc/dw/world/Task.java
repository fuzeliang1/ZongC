package com.zongc.dw.world;

/** 运输任务（订单）：把人或货从上车点送到目的地。 */
public final class Task {
    public enum State { WAITING, ASSIGNED, PICKED, DELIVERED, CANCELLED }

    public final String id;
    /** 上下车点（格索引）。所在格被路障 / 障碍封闭时，会改到附近的可通行车道格。 */
    public int pickup;
    public int dropoff;
    public final double createdAt;
    public double assignedAt = Double.NaN;
    public double pickedAt = Double.NaN;
    public double deliveredAt = Double.NaN;
    public State state = State.WAITING;
    public String agentId;

    public Task(String id, int pickup, int dropoff, double createdAt) {
        this.id = id;
        this.pickup = pickup;
        this.dropoff = dropoff;
        this.createdAt = createdAt;
    }

    public boolean active() {
        return state == State.WAITING || state == State.ASSIGNED || state == State.PICKED;
    }
}
