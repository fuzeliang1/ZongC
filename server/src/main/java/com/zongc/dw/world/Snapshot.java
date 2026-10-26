package com.zongc.dw.world;

import java.util.List;

/**
 * 推送给浏览器的世界快照（约 10 Hz）。字段含义见 docs/protocol.md 第 3.3 节。
 * 坐标单位：米；格子坐标 [x, y] 为整数格号。值为 null 的字段在 JSON 中省略。
 *
 * @param hidden     真实世界中存在、系统尚未感知到的障碍格（上帝视角，仅用于显示）
 * @param discovered 由车辆感知发现并加入全局地图的障碍格
 * @param suspect    疑似障碍格（占据概率 0.6~0.8，尚未确认）
 * @param scenario   正在运行的场景脚本，没有脚本时为 null
 * @param hotspots   需求热点，均匀需求时为 null
 * @param zones      各区域的需求预测（单/分钟）与空车调度状态
 * @param vision     最近一次图像识别结果的摘要，没有时为 null
 */
public record Snapshot(String type, double t, long wall, boolean paused, double timeScale,
                       String strategy, double ratePerMin,
                       List<AgentView> agents, List<TaskView> tasks, Metrics.View metrics,
                       List<EventLog.Entry> events, int[] blocked,
                       int[] hidden, int[] discovered, int[] suspect,
                       ScenarioRunner.View scenario, List<Demand.View> hotspots, Rebalancer.View zones,
                       VisionView vision) {

    /**
     * @param route 剩余路线上各格中心 [x0,y0,x1,y1,...]，从当前格开始
     * @param held  已获授权（预约）的格子 [cx0,cy0,cx1,cy1,...]
     * @param stop  停车点 [x,y]，没有路线时为 null
     * @param scan  最近一次激光雷达扫描，48 个距离（分米），射线 k 的角度 = theta − π + k·2π/48
     * @param seen  这次扫描识别到的 [车辆数, 未知障碍命中数]
     */
    public record AgentView(String id, int num, String driver, boolean online, String device,
                            String status, double x, double y, double theta, double v, double r,
                            String taskId, double[] route, int[] held, double[] stop, int routeId,
                            boolean blocked, int[] scan, int[] seen, String zone) {}

    public record TaskView(String id, String state, int[] from, int[] to, String agent, double age) {}

    /**
     * 图像处理模块最近一次识别的摘要（详细结果见 GET /api/vision/last）。
     *
     * @param seq     第几次识别，前端据此判断是否需要刷新图片
     * @param objects 识别到的目标数
     * @param mapped  映射到地图上的障碍格 [x, y]，没有时为 null
     */
    public record VisionView(int seq, String source, double t, int objects, String summary, int[] mapped) {}
}
