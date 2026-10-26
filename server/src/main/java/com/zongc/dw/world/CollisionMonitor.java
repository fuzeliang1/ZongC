package com.zongc.dw.world;

import com.zongc.dw.map.GridMap;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 碰撞监测：独立于交通管控的"裁判"，按真实几何判断是否发生碰撞并计数。
 * 一次接触只记一次（直到两车分开）。考核要求零碰撞，这个计数就是证据。
 */
final class CollisionMonitor {
    private static final double TOLERANCE = 0.02;
    private Set<String> contacts = new HashSet<>();
    private Set<String> wallContacts = new HashSet<>();
    private Set<String> barrierContacts = new HashSet<>();

    void check(World w) {
        List<Agent> agents = w.agents();
        GridMap map = w.map();
        Metrics m = w.metrics();
        double r = w.config().vehicle.bodyRadius;

        Set<String> now = new HashSet<>();
        for (int i = 0; i < agents.size(); i++) {
            Agent a = agents.get(i);
            for (int j = i + 1; j < agents.size(); j++) {
                Agent b = agents.get(j);
                double d = Math.hypot(a.x - b.x, a.y - b.y);
                m.minCarGap = Math.min(m.minCarGap, d - 2 * r);
                if (d >= 2 * r - TOLERANCE) continue;
                String key = a.id + "|" + b.id;
                now.add(key);
                if (contacts.contains(key)) continue;
                m.collisions++;
                w.events().warn(w.time(), "碰撞：" + a.id + " 与 " + b.id);
            }
        }
        contacts = now;

        Set<String> nowWall = new HashSet<>();
        for (Agent a : agents) {
            double dw = map.distanceToObstacle(a.x, a.y, r + 0.5);
            if (Double.isFinite(dw)) m.minWallGap = Math.min(m.minWallGap, dw - r);
            if (dw < r - 0.05) {
                nowWall.add(a.id);
                if (!wallContacts.contains(a.id)) {
                    m.obstacleHits++;
                    w.events().warn(w.time(), "撞墙：" + a.id + " 与建筑/边界发生碰撞");
                }
            }
        }
        wallContacts = nowWall;

        // 路障与未知障碍：按"真实世界"判断（包括系统还没发现的障碍），所以这是对感知 + 管控的独立检验
        Set<String> nowBarrier = new HashSet<>();
        Perception p = w.perception();
        for (Agent a : agents) {
            double best = Double.POSITIVE_INFINITY;
            int hit = -1;
            for (int c : map.cellsOverlappingCircle(a.x, a.y, r + 0.5)) {
                if (!map.type(c).drivable() || !p.solid(c)) continue;
                double d = map.distanceToCell(a.x, a.y, c);
                if (d < best) {
                    best = d;
                    hit = c;
                }
            }
            if (hit < 0) continue;
            m.minBarrierGap = Math.min(m.minBarrierGap, best - r);
            if (best < r - 0.05) {
                nowBarrier.add(a.id);
                if (!barrierContacts.contains(a.id)) {
                    m.barrierHits++;
                    w.events().warn(w.time(), "撞障碍：" + a.id + " 与 (" + map.cx(hit) + "," + map.cy(hit) + ") 的路障/障碍发生碰撞");
                }
            }
        }
        barrierContacts = nowBarrier;
    }
}
