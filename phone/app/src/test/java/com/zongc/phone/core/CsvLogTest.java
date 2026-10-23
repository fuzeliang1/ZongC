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
        s.x = 12.3456;
        s.y = -1;
        s.theta = Math.PI;
        s.steps = 42;
        s.walking = true;
        s.stepLength = Double.NaN;
        log.pose(s, 9, 4, true, 12.34);
        log.close();
        String[] lines = out.toString().split("\n");
        assertEquals(2, lines.length);
        String[] head = lines[0].split(",", -1);
        String[] row = lines[1].split(",", -1);
        assertEquals(head.length, row.length);
        assertEquals("2.500", row[0]);
        assertEquals("12.346", row[col(head, "x")]);
        assertEquals("-1.000", row[col(head, "y")]);
        assertEquals("3.1416", row[col(head, "theta")]);
        assertEquals("42", row[col(head, "steps")]);
        assertEquals("1", row[col(head, "walking")]);
        assertEquals("NaN", row[col(head, "step_len")]);
        assertEquals("NaN", row[col(head, "lat")]);
        assertEquals("9", row[col(head, "sats_used")]);
        assertEquals("4", row[col(head, "bds_used")]);
        assertEquals("12.3", row[col(head, "rtt_ms")]);
        assertEquals(2, log.rows());
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
