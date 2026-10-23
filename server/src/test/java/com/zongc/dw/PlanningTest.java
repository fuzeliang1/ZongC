package com.zongc.dw;

import com.zongc.dw.map.CellType;
import com.zongc.dw.map.Dir;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.map.MapLoader;
import com.zongc.dw.plan.AStarPlanner;
import com.zongc.dw.plan.DistanceField;
import com.zongc.dw.plan.Pose;
import com.zongc.dw.plan.Route;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 地图规则、A* 规划、路线几何的测试。 */
public class PlanningTest {

    @Test
    public void defaultMapHasNoDeadEnds() throws Exception {
        GridMap map = TestSupport.map(TestSupport.config());
        assertEquals(53, map.width());
        assertEquals(34, map.height());
        assertTrue(MapLoader.validate(map).isEmpty());
        assertTrue(map.laneCells().length > 500);
    }

    @Test
    public void laneRulesAreEnforced() throws Exception {
        GridMap map = TestSupport.map(TestSupport.config());
        // (5,0) 是向东车道：只能向东开
        int c = map.index(5, 0);
        assertEquals(CellType.LANE_E, map.type(c));
        assertEquals(map.index(6, 0), map.move(c, Dir.E));
        assertEquals(-1, map.move(c, Dir.W));
        assertEquals(-1, map.move(c, Dir.N));   // 不能横穿进向西车道
        // 路口格 (0,0) 可以向北驶入路口 (0,1)，但不能向东驶入"向西车道"以外的格子方向错误的车道
        assertEquals(map.index(0, 1), map.move(map.index(0, 0), Dir.N));
    }

    @Test
    public void astarFindsLegalShortestPaths() throws Exception {
        GridMap map = TestSupport.map(TestSupport.config());
        AStarPlanner noTurnCost = new AStarPlanner(map, 0.0);
        AStarPlanner planner = new AStarPlanner(map, 0.5);
        int[] lanes = map.laneCells();
        Random rng = new Random(7);
        for (int k = 0; k < 300; k++) {
            int a = lanes[rng.nextInt(lanes.length)], b = lanes[rng.nextInt(lanes.length)];
            int[] path = planner.plan(a, map.type(a).laneDir, b, null);
            assertNotNull("任意两个车道格之间都应可达", path);
            assertEquals(a, path[0]);
            assertEquals(b, path[path.length - 1]);
            Dir prev = map.type(a).laneDir;
            for (int i = 1; i < path.length; i++) {
                Dir d = Dir.fromDelta(map.cx(path[i]) - map.cx(path[i - 1]), map.cy(path[i]) - map.cy(path[i - 1]));
                assertNotNull("相邻格必须相邻", d);
                assertEquals("每一步都必须符合车道规则", path[i], map.move(path[i - 1], d));
                assertTrue("不允许原地掉头", prev == null || d != prev.opposite());
                prev = d;
            }
            // 不计转弯代价时，A* 的步数应等于距离场（BFS）给出的最短步数
            int[] shortest = noTurnCost.plan(a, map.type(a).laneDir, b, null);
            int[] field = DistanceField.toGoal(map, b);
            assertTrue(shortest.length - 1 >= field[a]);
            assertTrue("A* 应找到最短路（允许因禁止掉头多走少量步）", shortest.length - 1 <= field[a] + 4);
        }
    }

    @Test
    public void routeGeometryIsContinuousAndInsideRoads() throws Exception {
        GridMap map = TestSupport.map(TestSupport.config());
        AStarPlanner planner = new AStarPlanner(map, 0.5);
        int[] lanes = map.laneCells();
        Random rng = new Random(11);
        for (int k = 0; k < 100; k++) {
            int a = lanes[rng.nextInt(lanes.length)], b = lanes[rng.nextInt(lanes.length)];
            Route r = new Route(map, planner.plan(a, null, b, null), null);
            Pose prev = r.poseAt(0);
            for (double s = 0.02; s <= r.length(); s += 0.02) {
                Pose p = r.poseAt(s);
                assertTrue("位置应连续", Math.hypot(p.x() - prev.x(), p.y() - prev.y()) <= 0.0201);
                // 车辆中心离建筑至少 0.2 米（半格减去转弯圆弧的内切量）
                assertTrue(map.distanceToObstacle(p.x(), p.y(), 1.0) >= 0.2 - 1e-9);
                prev = p;
            }
            // 设备路点折线长度单调递增，且与真实弧长相差很小
            double[][] poly = r.devicePolyline();
            for (int i = 1; i < poly[3].length; i++) assertTrue(poly[3][i] > poly[3][i - 1]);
            assertEquals(r.length(), poly[3][poly[3].length - 1], 0.05 * r.length() + 0.01);
            // 投影是 poseAt 的逆运算
            double s = r.length() * 0.37;
            Pose p = r.poseAt(s);
            assertEquals(s, r.project(p.x(), p.y(), 0, r.last()), 1e-6);
        }
    }
}
