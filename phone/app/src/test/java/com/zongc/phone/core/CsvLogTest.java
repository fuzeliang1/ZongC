package com.zongc.phone.core;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CsvLogTest {

    @Test
    public void poseRowsMatchTheHeader() {
        StringWriter out = new StringWriter();
        CsvLog log = new CsvLog(out, CsvLog.POSE_HEADER, 1_000_000_000L);
        PdrState s = new PdrState();
        s.t = 3_500_000_000L;
        s.px = 1.25;
        s.yawP = Math.PI;
        s.v = 1.1;
        s.steps = 42;
        s.walking = true;
        s.stepLength = Double.NaN;
        RouteFollower.Status car = new RouteFollower.Status();
        car.ready = true;
        car.x = 12.3456;
        car.y = -1;
        car.theta = Math.PI / 2;
        car.hasRoute = true;
        car.s = 3.5;
        car.stopS = 6;
        log.pose(s, car, 9, 4, true, 12.34);
        log.pose(s, null, -1, -1, false, Double.NaN);
        log.close();
        String[] lines = out.toString().split("\n");
        assertEquals(3, lines.length);
        String[] head = lines[0].split(",", -1);
        String[] row = lines[1].split(",", -1);
        assertEquals(head.length, row.length);
        assertEquals("2.500", row[0]);
        assertEquals("12.346", row[col(head, "car_x")]);
        assertEquals("-1.000", row[col(head, "car_y")]);
        assertEquals("1.5708", row[col(head, "car_theta")]);
        assertEquals("3.500", row[col(head, "car_s")]);
        assertEquals("6.000", row[col(head, "car_stop_s")]);
        assertEquals("1.100", row[col(head, "walk_v")]);
        assertEquals("1.250", row[col(head, "px")]);
        assertEquals("3.1416", row[col(head, "yaw_p")]);
        assertEquals("42", row[col(head, "steps")]);
        assertEquals("1", row[col(head, "walking")]);
        assertEquals("NaN", row[col(head, "step_len")]);
        assertEquals("NaN", row[col(head, "lat")]);
        assertEquals("9", row[col(head, "sats_used")]);
        assertEquals("4", row[col(head, "bds_used")]);
        assertEquals("12.3", row[col(head, "rtt_ms")]);
        String[] unbound = lines[2].split(",", -1);
        assertEquals(head.length, unbound.length);
        assertEquals("还没绑定数字车：车的列为 NaN", "NaN", unbound[col(head, "car_x")]);
        assertEquals("1.250", unbound[col(head, "px")]);
        assertEquals(3, log.rows());
    }

    @Test
    public void imuRows() {
        StringWriter out = new StringWriter();
        CsvLog log = new CsvLog(out, CsvLog.IMU_HEADER, 0);
        log.imu(20_000_000L, CsvLog.GYRO, 0.1, -0.25, 9.80665);
        assertEquals("t_s,sensor,x,y,z\n0.0200,2,0.10000,-0.25000,9.80665\n", out.toString());
    }

    @Test
    public void writeErrorsAreRecordedNotThrown() {
        Writer broken = new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("存储空间不足");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        CsvLog log = new CsvLog(broken, CsvLog.IMU_HEADER, 0);
        log.imu(1, CsvLog.ACC, 1, 2, 3);
        assertNotNull(log.error());
        assertTrue(log.error().contains("存储空间不足"));
        log.close();
    }

    private static int col(String[] head, String name) {
        for (int i = 0; i < head.length; i++) if (head[i].equals(name)) return i;
        throw new AssertionError("表头里没有 " + name);
    }
}
