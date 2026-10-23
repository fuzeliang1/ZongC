package com.zongc.dw.world;

import com.zongc.dw.config.Config;
import com.zongc.dw.plan.Pose;
import com.zongc.dw.plan.Route;

/**
 * 内部仿真车的运动模型：沿平滑路线行驶，速度受三类约束——
 * 最高速度、转弯限速、以及"在授权终点前刹停"（v ≤ √(2·a·d)）；加速度受限。
 * 这样车辆永远不会驶出已授权的格子，碰撞安全由交通管控保证。
 */
final class InternalDriver {
    private final Config.Vehicle vc;

    InternalDriver(Config.Vehicle vc) {
        this.vc = vc;
    }

    /**
     * @param sStop 本步允许到达的最远弧长（由交通管控根据授权计算）
     */
    void advance(Agent a, double dt, double sStop) {
        Route r = a.route;
        if (r == null || a.status == AgentStatus.BOARDING || a.status == AgentStatus.ALIGHTING) {
            a.v = 0;
            a.omega = 0;
            return;
        }
        double s = a.s;
        double vAllowed = vc.vMax;
        vAllowed = Math.min(vAllowed, Math.sqrt(Math.max(0, 2 * vc.aDec * (sStop - s))));

        // 前方转弯：提前减速到转弯限速
        double look = vc.vMax * vc.vMax / (2 * vc.aDec) + 0.5;
        for (int i = r.pieceAt(s); i <= r.last(); i++) {
            double gap = r.sStart(i) - s;
            if (gap > look) break;
            if (!r.isArc(i)) continue;
            if (gap <= 0) vAllowed = Math.min(vAllowed, vc.vTurn);
            else vAllowed = Math.min(vAllowed, Math.sqrt(vc.vTurn * vc.vTurn + 2 * vc.aDec * gap));
        }

        double vNew = Math.max(0, Math.min(a.v + vc.aAcc * dt, vAllowed));
        double sNew = s + vNew * dt;
        if (sNew > sStop) sNew = Math.max(s, sStop);
        if (sStop - sNew < 0.003) {   // 到达停车点：精确停住，避免无限逼近
            sNew = Math.max(s, sStop);
            vNew = 0;
        }

        Pose p = r.poseAt(sNew);
        double th = Double.isNaN(p.theta()) ? a.theta : p.theta();
        a.omega = Route.normalize(th - a.theta) / dt;
        a.x = p.x();
        a.y = p.y();
        a.theta = th;
        a.s = sNew;
        a.v = vNew;
    }
}
