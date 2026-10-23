package com.zongc.dw.dispatch;

import com.zongc.dw.config.Config;

import java.util.List;

/**
 * 派单策略接口（优化与决策模块）。新增策略只需实现本接口并在 {@link #of} 中注册。
 */
public interface Dispatcher {

    String name();

    /**
     * 为空闲车辆和等待中的订单做匹配。
     *
     * @param cost    cost[i][j] = 第 i 辆空车去接第 j 个订单的行驶距离（米），不可达为 +∞
     * @param waitSec waitSec[j] = 第 j 个订单已经等待的时间（秒）
     * @return 匹配结果列表，每项为 {车辆下标, 订单下标}
     */
    List<int[]> assign(double[][] cost, double[] waitSec);

    static Dispatcher of(String strategy, Config.Dispatch cfg) {
        return switch (strategy) {
            case "GREEDY" -> new GreedyDispatcher();
            case "HUNGARIAN" -> new HungarianDispatcher(cfg.waitWeight);
            default -> throw new IllegalArgumentException("未知派单策略: " + strategy);
        };
    }
}
