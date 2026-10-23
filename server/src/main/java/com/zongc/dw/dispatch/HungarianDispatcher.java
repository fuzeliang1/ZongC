package com.zongc.dw.dispatch;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量最优派单：每个派单周期把"空车 × 等待订单"建成代价矩阵，用匈牙利算法求总代价最小的匹配。
 *
 * <p>代价 = 接驾距离 − waitWeight × 已等待时间。减去等待时间项是为了公平：
 * 否则偏远的订单可能一直被"更划算"的订单挤掉而长期等待。</p>
 */
public final class HungarianDispatcher implements Dispatcher {
    private static final double INFEASIBLE = 1e7;
    private final double waitWeight;

    public HungarianDispatcher(double waitWeight) {
        this.waitWeight = waitWeight;
    }

    @Override
    public String name() {
        return "HUNGARIAN";
    }

    @Override
    public List<int[]> assign(double[][] cost, double[] waitSec) {
        List<int[]> result = new ArrayList<>();
        int cars = cost.length;
        int tasks = waitSec.length;
        if (cars == 0 || tasks == 0) return result;
        double[][] m = new double[cars][tasks];
        for (int i = 0; i < cars; i++) {
            for (int j = 0; j < tasks; j++) {
                m[i][j] = Double.isFinite(cost[i][j]) ? cost[i][j] - waitWeight * waitSec[j] : INFEASIBLE;
            }
        }
        int[] rowToCol = Hungarian.solve(m);
        for (int i = 0; i < cars; i++) {
            int j = rowToCol[i];
            if (j >= 0 && Double.isFinite(cost[i][j])) result.add(new int[]{i, j});
        }
        return result;
    }
}
