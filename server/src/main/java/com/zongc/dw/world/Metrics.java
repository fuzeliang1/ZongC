package com.zongc.dw.world;

import com.zongc.dw.util.Num;

/** 运行指标：用于界面统计面板、效果评估和算法对比实验。 */
public final class Metrics {
    public int tasksCreated, tasksAssigned, tasksPicked, tasksDelivered, tasksCancelled;
    public double sumWait, maxWait, sumRide, sumTotal;
    /** 车—车碰撞次数（必须为 0）。 */
    public int collisions;
    /** 车—建筑碰撞次数（必须为 0）。 */
    public int obstacleHits;
    /** 车—路障 / 未知障碍碰撞次数（必须为 0；按真实世界判断，包括系统还不知道的障碍）。 */
    public int barrierHits;
    /** 出租车与自由智能体（手机/行人）的接触次数。 */
    public int freeContacts;
    public int reroutes;
    /** 掉头次数（前方道路被堵死、无路可走时借对向车道掉头）。 */
    public int uturns;
    /** 外部设备越过授权终点的次数。 */
    public int authorityViolations;
    public int dispatchRounds;
    public double lastDispatchMs;
    public double distEmpty, distLoaded;
    /** 运行以来出租车之间车身最小间隙（米），负数表示发生过重叠。 */
    public double minCarGap = Double.POSITIVE_INFINITY;
    /** 运行以来出租车与建筑/边界的最小间隙（米），只统计靠近障碍物时。 */
    public double minWallGap = Double.POSITIVE_INFINITY;
    /** 运行以来出租车与路障 / 障碍（真实世界）的最小间隙（米），只统计靠近时。 */
    public double minBarrierGap = Double.POSITIVE_INFINITY;
    /** 最长连续受阻时间（秒），用于判断是否出现死锁。 */
    public double maxBlocked;

    public void onPicked(Task t) {
        tasksPicked++;
        double w = t.pickedAt - t.createdAt;
        sumWait += w;
        maxWait = Math.max(maxWait, w);
    }

    public void onDelivered(Task t) {
        tasksDelivered++;
        sumRide += t.deliveredAt - t.pickedAt;
        sumTotal += t.deliveredAt - t.createdAt;
    }

    /**
     * 推送给前端 / 实验输出的视图。
     *
     * @param discoveries     由感知发现、加入全局地图的障碍数
     * @param obstacleClears  由感知确认消失、从地图撤掉的障碍数
     * @param avgDiscoverySec 障碍出现到被发现的平均时间（秒）
     * @param coverage        最近 60 s 内被车辆传感器核实过的可行驶格比例
     * @param rebalanceMoves  空车调度次数
     * @param visionReports   图像识别上报次数
     */
    public record View(double simTime, int created, int assigned, int picked, int delivered, int cancelled,
                       int waiting, double avgWait, double maxWait, double avgRide, double avgTotal,
                       double deliveredPerMin, double emptyRatio, double distEmpty, double distLoaded,
                       int collisions, int obstacleHits, int barrierHits, int freeContacts, int reroutes,
                       int authorityViolations, int dispatchRounds, double lastDispatchMs,
                       Double minCarGap, Double minWallGap, Double minBarrierGap, double maxBlocked,
                       int taxis, int externalTaxis, int freeAgents,
                       int discoveries, int obstacleClears, double avgDiscoverySec, double coverage,
                       int rebalanceMoves, int visionReports, int uturns) {}

    View view(double time, int waiting, int taxis, int externalTaxis, int freeAgents,
              Perception p, double coverage, int rebalanceMoves, int visionReports) {
        double avgWait = tasksPicked > 0 ? sumWait / tasksPicked : 0;
        double avgRide = tasksDelivered > 0 ? sumRide / tasksDelivered : 0;
        double avgTotal = tasksDelivered > 0 ? sumTotal / tasksDelivered : 0;
        double perMin = time > 1 ? tasksDelivered / (time / 60.0) : 0;
        double dist = distEmpty + distLoaded;
        double emptyRatio = dist > 0 ? distEmpty / dist : 0;
        return new View(Num.r1(time), tasksCreated, tasksAssigned, tasksPicked, tasksDelivered, tasksCancelled,
                waiting, Num.r1(avgWait), Num.r1(maxWait), Num.r1(avgRide), Num.r1(avgTotal),
                Math.round(perMin * 100) / 100.0, Math.round(emptyRatio * 1000) / 1000.0,
                Num.r1(distEmpty), Num.r1(distLoaded), collisions, obstacleHits, barrierHits, freeContacts, reroutes,
                authorityViolations, dispatchRounds, Math.round(lastDispatchMs * 100) / 100.0,
                gap(minCarGap), gap(minWallGap), gap(minBarrierGap), Num.r1(maxBlocked),
                taxis, externalTaxis, freeAgents,
                p.discoveries, p.clears, Num.r1(p.avgDiscoverySec()), Math.round(coverage * 1000) / 1000.0,
                rebalanceMoves, visionReports, uturns);
    }

    /** 尚未测到时返回 null（JSON 中省略该字段）。 */
    private static Double gap(double v) {
        return Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : null;
    }
}
