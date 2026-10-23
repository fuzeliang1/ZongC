package com.zongc.dw.dispatch;

import java.util.Arrays;

/**
 * 匈牙利算法（Kuhn–Munkres，带势函数的 O(n²m) 实现），求解最小代价指派问题：
 * 在 n×m 代价矩阵中为每一行选一个不同的列，使总代价最小。
 */
public final class Hungarian {
    private Hungarian() {}

    /**
     * 求解矩形指派问题。
     *
     * @param a 代价矩阵（不能含 ∞，用一个很大的有限数表示不可行）
     * @return rowToCol[i] = 第 i 行分配到的列；行数多于列数时，未分配的行为 -1
     */
    public static int[] solve(double[][] a) {
        int rows = a.length;
        if (rows == 0) return new int[0];
        int cols = a[0].length;
        if (cols == 0) {
            int[] none = new int[rows];
            Arrays.fill(none, -1);
            return none;
        }
        if (rows <= cols) return solveRowsLeCols(a);
        // 行比列多：转置后求解，再把结果翻转回来
        double[][] t = new double[cols][rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) t[j][i] = a[i][j];
        }
        int[] colToRow = solveRowsLeCols(t);
        int[] rowToCol = new int[rows];
        Arrays.fill(rowToCol, -1);
        for (int j = 0; j < cols; j++) rowToCol[colToRow[j]] = j;
        return rowToCol;
    }

    /** 要求 n ≤ m。经典实现，下标从 1 开始，第 0 列是辅助列。 */
    private static int[] solveRowsLeCols(double[][] a) {
        int n = a.length, m = a[0].length;
        double[] u = new double[n + 1], v = new double[m + 1];
        int[] p = new int[m + 1], way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[m + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0], j1 = 0;
                double delta = Double.POSITIVE_INFINITY;
                for (int j = 1; j <= m; j++) {
                    if (used[j]) continue;
                    double cur = a[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] rowToCol = new int[n];
        Arrays.fill(rowToCol, -1);
        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) rowToCol[p[j] - 1] = j - 1;
        }
        return rowToCol;
    }
}
