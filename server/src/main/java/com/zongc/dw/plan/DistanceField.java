package com.zongc.dw.plan;

import com.zongc.dw.map.Dir;
import com.zongc.dw.map.GridMap;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 距离场：一次反向广度优先搜索，得到"地图上每个格子开到 goal 需要几步"。
 * 派单时对每个订单的上车点算一次，就能 O(1) 查询任意车辆的接驾距离。
 * 遵守车道方向，不计转弯代价，不可达的格子值为 -1。
 */
public final class DistanceField {
    private DistanceField() {}

    public static int[] toGoal(GridMap map, int goal) {
        int[] dist = new int[map.size()];
        Arrays.fill(dist, -1);
        if (!map.passable(goal)) return dist;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        dist[goal] = 0;
        queue.add(goal);
        while (!queue.isEmpty()) {
            int c = queue.poll();
            int x = map.cx(c), y = map.cy(c);
            for (Dir d : Dir.values()) {
                // 前驱格 p 满足：从 p 沿 d 走一步到达 c
                int px = x - d.dx, py = y - d.dy;
                if (!map.inBounds(px, py)) continue;
                int p = map.index(px, py);
                if (dist[p] >= 0 || !map.passable(p)) continue;
                if (map.move(p, d) != c) continue;
                dist[p] = dist[c] + 1;
                queue.add(p);
            }
        }
        return dist;
    }
}
