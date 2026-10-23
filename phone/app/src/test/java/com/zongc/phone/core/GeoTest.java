package com.zongc.phone.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GeoTest {

    @Test
    public void wrapsAngles() {
        assertEquals(0, Geo.wrap(2 * Math.PI), 1e-12);
        assertEquals(Math.PI, Geo.wrap(-Math.PI), 1e-12);
        assertEquals(-Math.PI / 2, Geo.wrap(3 * Math.PI / 2), 1e-12);
        assertEquals(0.1, Geo.wrap(0.1 + 20 * Math.PI), 1e-9);
        assertEquals(-0.2, Geo.diff(Math.PI - 0.1, -Math.PI + 0.1), 1e-12);
    }

    @Test
    public void enuMatchesHaversineAroundTongji() {
        double lat0 = 31.2850, lon0 = 121.5020;          // 同济大学四平路校区附近
        double lat = 31.2895, lon = 121.5080;
        double[] en = Geo.enu(lat0, lon0, lat, lon);
        double d = Geo.haversine(lat0, lon0, lat, lon);
        assertEquals(d, Math.hypot(en[0], en[1]), d * 1e-3);
        assertTrue(en[0] > 0 && en[1] > 0);
        // 纬度 1e-4 度 ≈ 11.1 米
        assertEquals(11.12, Geo.enu(lat0, lon0, lat0 + 1e-4, lon0)[1], 0.01);
    }

    @Test
    public void arrowsPointTheRightWay() {
        assertEquals("→", Geo.arrow(0));
        assertEquals("↑", Geo.arrow(Math.PI / 2));
        assertEquals("←", Geo.arrow(Math.PI));
        assertEquals("↓", Geo.arrow(-Math.PI / 2));
        assertEquals("↘", Geo.arrow(-Math.PI / 4));
    }
}
