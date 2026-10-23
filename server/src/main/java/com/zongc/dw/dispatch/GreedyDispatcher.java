package com.zongc.dw.dispatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 贪心派单（基线算法）：订单按先来后到排序，每个订单派给当前离它最近的空车。
 * 简单、实时性好，但只看眼前，整体接驾距离通常不是最优。
 */
public final class GreedyDispatcher implements Dispatcher {

    @Override
    public String name() {
        return "GREEDY";
    }

    @Override
    public List<int[]> assign(double[][] cost, double[] waitSec) {
        List<int[]> result = new ArrayList<>();
        int cars = cost.length;
        if (cars == 0) return result;
        int tasks = waitSec.length;
        Integer[] order = new Integer[tasks];
        for (int j = 0; j < tasks; j++) order[j] = j;
        Arrays.sort(order, Comparator.comparingDouble(j -> -waitSec[j]));   // 等得最久的先派

        boolean[] used = new boolean[cars];
        for (int j : order) {
            int best = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int i = 0; i < cars; i++) {
                if (!used[i] && cost[i][j] < bestCost) {
                    bestCost = cost[i][j];
                    best = i;
                }
            }
            if (best >= 0) {
                used[best] = true;
                result.add(new int[]{best, j});
            }
        }
        return result;
    }
}
