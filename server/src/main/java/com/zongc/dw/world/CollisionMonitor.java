package com.zongc.dw.world;

import com.zongc.dw.map.GridMap;
import com.zongc.dw.traffic.TrafficController;

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

    void check(World w) {
        List<Agent> agents = w.agents();
        TrafficController traffic = w.traffic();
        GridMap map = w.map();
        Metrics m = w.metrics();

        Set<String> now = new HashSet<>();
        for (int i = 0; i < agents.size(); i++) {
            Agent a = agents.get(i);
            for (int j = i + 1; j < agents.size(); j++) {
                Agent b = agents.get(j);
                if (!a.taxi && !b.taxi) continue;
                double d = Math.hypot(a.x - b.x, a.y - b.y);
                if (a.taxi && b.taxi) m.minCarGap = Math.min(m.minCarGap, d - traffic.radiusOf(a) - traffic.radiusOf(b));
                double limit = traffic.radiusOf(a) + traffic.radiusOf(b) - TOLERANCE;
                if (!a.taxi || !b.taxi) limit = 2 * w.config().vehicle.bodyRadius - TOLERANCE;
                if (d >= limit) continue;
                String key = a.id + "|" + b.id;
                now.add(key);
                if (contacts.contains(key)) continue;
                if (a.taxi && b.taxi) {
                    m.collisions++;
                    w.events().warn(w.time(), "碰撞：" + a.id + " 与 " + b.id);
                } else {
                    m.freeContacts++;
                    w.events().warn(w.time(), "接触：" + a.id + " 与 " + b.id + "（自由智能体）");
                }
            }
        }
        contacts = now;

        Set<String> nowWall = new HashSet<>();
        double r = w.config().vehicle.bodyRadius;
        for (Agent a : agents) {
            if (!a.taxi) continue;
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
    }
}
