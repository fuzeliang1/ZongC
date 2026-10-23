package com.zongc.dw;

import com.zongc.dw.dispatch.GreedyDispatcher;
import com.zongc.dw.dispatch.Hungarian;
import com.zongc.dw.dispatch.HungarianDispatcher;
import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 派单算法测试：匈牙利算法与暴力枚举对比。 */
public class DispatchTest {

    @Test
    public void hungarianMatchesBruteForce() {
        Random rng = new Random(3);
        for (int trial = 0; trial < 300; trial++) {
            int rows = 1 + rng.nextInt(6), cols = 1 + rng.nextInt(6);
            double[][] a = new double[rows][cols];
            for (double[] row : a) for (int j = 0; j < cols; j++) row[j] = rng.nextInt(50) - 10;
            int[] sol = Hungarian.solve(a);
            assertEquals(brute(a), cost(a, sol), 1e-9);
            // 每列最多被分配一次，分配数 = min(rows, cols)
            boolean[] used = new boolean[cols];
            int assigned = 0;
            for (int j : sol) {
                if (j < 0) continue;
                assertTrue(!used[j]);
                used[j] = true;
                assigned++;
            }
            assertEquals(Math.min(rows, cols), assigned);
        }
    }

    @Test
    public void hungarianBeatsOrTiesGreedy() {
        Random rng = new Random(5);
        for (int trial = 0; trial < 200; trial++) {
            int cars = 2 + rng.nextInt(6), tasks = 2 + rng.nextInt(6);
            double[][] cost = new double[cars][tasks];
            for (double[] row : cost) for (int j = 0; j < tasks; j++) row[j] = rng.nextInt(60);
            double[] wait = new double[tasks];
            List<int[]> g = new GreedyDispatcher().assign(cost, wait);
            List<int[]> h = new HungarianDispatcher(0).assign(cost, wait);
            assertEquals(g.size(), h.size());
            assertTrue(total(cost, h) <= total(cost, g) + 1e-9);
        }
    }

    @Test
    public void unreachablePairsAreNeverAssigned() {
        double inf = Double.POSITIVE_INFINITY;
        double[][] cost = {{inf, 5}, {inf, 3}};
        List<int[]> h = new HungarianDispatcher(0.3).assign(cost, new double[]{10, 0});
        assertEquals(1, h.size());
        assertEquals(1, h.get(0)[1]);
    }

    private static double total(double[][] cost, List<int[]> pairs) {
        double s = 0;
        for (int[] p : pairs) s += cost[p[0]][p[1]];
        return s;
    }

    private static double cost(double[][] a, int[] sol) {
        double s = 0;
        for (int i = 0; i < sol.length; i++) if (sol[i] >= 0) s += a[i][sol[i]];
        return s;
    }

    private static double brute(double[][] a) {
        int rows = a.length, cols = a[0].length;
        if (rows <= cols) return bruteRows(a, 0, new boolean[cols]);
        double[][] t = new double[cols][rows];
        for (int i = 0; i < rows; i++) for (int j = 0; j < cols; j++) t[j][i] = a[i][j];
        return bruteRows(t, 0, new boolean[rows]);
    }

    private static double bruteRows(double[][] a, int i, boolean[] used) {
        if (i == a.length) return 0;
        double best = Double.POSITIVE_INFINITY;
        for (int j = 0; j < used.length; j++) {
            if (used[j]) continue;
            used[j] = true;
            best = Math.min(best, a[i][j] + bruteRows(a, i + 1, used));
            used[j] = false;
        }
        return best;
    }
}
