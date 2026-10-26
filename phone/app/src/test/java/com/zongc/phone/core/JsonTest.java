package com.zongc.phone.core;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JsonTest {

    @Test
    public void parsesNestedStructures() {
        Map<String, Object> o = Json.parseObject(
                " {\"type\":\"welcome\",\"agentId\":\"car-6\",\"pose\":{\"x\":12.5,\"y\":-3e-1},"
                        + "\"list\":[1,2.5,true,false,null,\"s\"],\"empty\":{},\"none\":[]} ");
        assertEquals("welcome", Json.str(o, "type"));
        assertEquals("car-6", Json.str(o, "agentId"));
        Map<String, Object> pose = Json.obj(o.get("pose"));
        assertEquals(12.5, Json.num(pose, "x", 0), 1e-12);
        assertEquals(-0.3, Json.num(pose, "y", 0), 1e-12);
        List<Object> list = Json.arr(o.get("list"));
        assertEquals(6, list.size());
        assertEquals(Boolean.TRUE, list.get(2));
        assertNull(list.get(4));
        assertEquals("s", list.get(5));
        assertTrue(Json.obj(o.get("empty")).isEmpty());
        assertTrue(Json.arr(o.get("none")).isEmpty());
    }

    @Test
    public void decodesEscapesAndUnicode() {
        Map<String, Object> o = Json.parseObject("{\"msg\":\"a\\\"b\\\\c\\/d\\n\\u6570\\u5b57\",\"raw\":\"数字世界\"}");
        assertEquals("a\"b\\c/d\n数字", Json.str(o, "msg"));
        assertEquals("数字世界", Json.str(o, "raw"));
    }

    @Test
    public void helpersHandleMissingAndWrongTypes() {
        Map<String, Object> o = Json.parseObject("{\"n\":3,\"s\":\"x\",\"b\":true,\"arr\":[1,\"x\"]}");
        assertEquals(7.0, Json.num(o, "missing", 7.0), 0);
        assertEquals(7.0, Json.num(o, "s", 7.0), 0);
        assertEquals("3", Json.str(o, "n"));
        assertTrue(Json.bool(o, "b", false));
        assertFalse(Json.bool(o, "n", false));
        assertNull(Json.nums(o, "arr"));
        assertNull(Json.obj("text"));
        assertNull(Json.str(null, "x"));
    }

    @Test
    public void rejectsMalformedInput() {
        String[] bad = {"", "{", "{\"a\":}", "[1,2", "{\"a\":1}x", "tru", "{a:1}", "\"abc", "[1,]x"};
        for (String s : bad) {
            try {
                Json.parse(s);
                fail("应当拒绝: " + s);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void writerProducesValidCompactJson() {
        String s = new Json.Obj()
                .put("type", "state")
                .put("x", 12.34567, 3)
                .put("y", -0.0004, 3)
                .put("nan", Double.NaN, 3)
                .put("n", 42)
                .put("ok", true)
                .put("text", "引号\"和\n换行")
                .put("nil", (String) null)
                .raw("arr", "[1,2]")
                .toString();
        assertEquals("{\"type\":\"state\",\"x\":12.346,\"y\":0.000,\"nan\":null,\"n\":42,\"ok\":true,"
                + "\"text\":\"引号\\\"和\\n换行\",\"nil\":null,\"arr\":[1,2]}", s);
        Map<String, Object> back = Json.parseObject(s);
        assertEquals("引号\"和\n换行", Json.str(back, "text"));
        assertEquals(12.346, Json.num(back, "x", 0), 1e-12);
    }

    @Test
    public void fixedFormattingIsLocaleIndependent() {
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);   // 德语区小数点是逗号
            assertEquals("3.142", Fmt.json(Math.PI, 3));
            assertEquals("-2.50", Fmt.json(-2.5, 2));
            assertEquals("0.001", Fmt.json(0.0005, 3));
            assertEquals("-0.001", Fmt.json(-0.0005, 3));
            assertEquals("7", Fmt.json(7.4, 0));
            assertEquals("null", Fmt.json(Double.POSITIVE_INFINITY, 2));
            assertEquals("—", Fmt.show(Double.NaN, 1));
            assertEquals("123456.7", Fmt.show(123456.66, 1));
        } finally {
            java.util.Locale.setDefault(old);
        }
    }
}
