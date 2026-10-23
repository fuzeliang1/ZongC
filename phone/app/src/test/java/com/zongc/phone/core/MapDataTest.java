package com.zongc.phone.core;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MapDataTest {

    static String fixture(String name) throws IOException {
        InputStream in = MapDataTest.class.getResourceAsStream("/fixtures/" + name);
        assertNotNull("缺少测试数据 " + name, in);
        return MapClient.readAll(in);
    }

    @Test
    public void parsesServerMapWithNorthRowFirst() throws IOException {
        MapData m = MapData.fromJson(fixture("map.json"));
        assertEquals(53, m.width);
        assertEquals(34, m.height);
        assertEquals(1.0, m.resolution, 0);
        assertEquals(53.0, m.worldWidth(), 0);
        // 地图文件第一行（最北边）是 "++<<<<…"，对应 y = 33
        assertTrue(m.isCross(0, 33));
        assertEquals('<', m.at(2, 33));
        assertEquals('>', m.at(2, 32));
        // 第三行起是建筑和南北向车道
        assertEquals('v', m.at(0, 31));
        assertEquals('^', m.at(1, 31));
        assertTrue(m.isBuilding(2, 31));
        // 超出地图按建筑处理
        assertTrue(m.isBuilding(-1, 0));
        assertTrue(m.isBuilding(53, 0));
    }

    @Test
    public void tinyMapOrientation() {
        String json = "{\"name\":\"t\",\"width\":3,\"height\":2,\"resolution\":2.0,\"rows\":[\"#+>\",\"<v^\"]}";
        MapData m = MapData.fromJson(json);
        assertEquals('<', m.at(0, 0));          // 最后一行是 y = 0
        assertEquals('#', m.at(0, 1));
        assertEquals(1, m.cellX(3.9));
        assertEquals(0, m.cellY(1.99));
        assertArrayEquals(new double[]{6, 0}, m.clamp(10, -1), 0);
    }

    @Test
    public void nearestLaneSkipsBuildingsCrossingsAndRoadblocks() throws IOException {
        MapData m = MapData.fromJson(fixture("map.json"));
        // (2.6, 5.4) 在建筑里，最近的车道是左侧北行车道 (1, 5)
        int[] c = m.nearestLane(2.6, 5.4, null);
        assertNotNull(c);
        assertTrue(m.isLane(c[0], c[1]));
        assertArrayEquals(new int[]{1, 5}, c);
        // 把那一格设为路障后，换成相邻车道格 (1, 4)
        int[] c2 = m.nearestLane(2.6, 5.4, new int[]{1, 5});
        assertArrayEquals(new int[]{1, 4}, c2);
        // 路口格不能作为上下车点
        int[] c3 = m.nearestLane(0.5, 33.5, null);
        assertFalse(m.isCross(c3[0], c3[1]));
        // 叫车：站在车道 (1, 5) 上时，上车点不能是自己脚下这一格
        int[] c4 = m.nearestLane(1.5, 5.5, null, 1.2);
        assertTrue(m.isLane(c4[0], c4[1]));
        assertTrue(Math.hypot(c4[0] + 0.5 - 1.5, c4[1] + 0.5 - 5.5) >= 1.2);
    }

    @Test
    public void noLaneReturnsNull() {
        MapData m = MapData.fromJson("{\"width\":2,\"height\":1,\"resolution\":1,\"rows\":[\"#+\"]}");
        assertNull(m.nearestLane(0, 0, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInconsistentRows() {
        MapData.fromJson("{\"width\":3,\"height\":2,\"resolution\":1,\"rows\":[\"###\",\"##\"]}");
    }

    @Test
    public void parsesServerSnapshot() throws IOException {
        WorldSnapshot s = WorldSnapshot.fromJson(fixture("snapshot.json"));
        assertEquals(8, s.agents.size());
        assertEquals(8, s.taxiCount());
        WorldSnapshot.Agent car1 = s.agent("car-1");
        assertNotNull(car1);
        assertTrue(car1.taxi);
        assertEquals(1, car1.num);
        assertEquals(0.3, car1.r, 1e-9);
        assertNotNull(car1.route);
        assertEquals(0, car1.route.length % 2);
        assertEquals(4, s.tasks.size());
        WorldSnapshot.Task t = s.tasks.get(0);
        assertNotNull(t.id);
        assertNotNull(t.agent);
        assertTrue(m(t.fromX, t.fromY) && m(t.toX, t.toY));
        assertSame(t, s.task(t.id));
        assertArrayEquals(new int[]{5, 8}, s.blocked);
        assertNull(s.agent("phone-9"));
    }

    private static boolean m(int x, int y) {
        return x >= 0 && x < 53 && y >= 0 && y < 34;
    }

    private static void assertSame(Object a, Object b) {
        org.junit.Assert.assertSame(a, b);
    }
}
