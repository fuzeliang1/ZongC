package com.zongc.dw.world;

import java.util.List;

/**
 * 推送给浏览器的世界快照（约 10 Hz）。字段含义见 docs/protocol.md 第 4 节。
 * 坐标单位：米；格子坐标 [x, y] 为整数格号。
 */
public record Snapshot(String type, double t, long wall, boolean paused, double timeScale,
                       String strategy, double ratePerMin,
                       List<AgentView> agents, List<TaskView> tasks, Metrics.View metrics,
                       List<EventLog.Entry> events, int[] blocked) {

    /**
     * @param route 剩余路线上各格中心 [x0,y0,x1,y1,...]，从当前格开始
     * @param held  已获授权（预约）的格子 [cx0,cy0,cx1,cy1,...]
     * @param stop  停车点 [x,y]，没有路线时为 null
     */
    public record AgentView(String id, int num, boolean taxi, String driver, boolean online, String device,
                            String status, double x, double y, double theta, double v, double r,
                            String taskId, double[] route, int[] held, double[] stop, int routeId,
                            boolean blocked) {}

    public record TaskView(String id, String state, int[] from, int[] to, String agent, double age) {}
}
