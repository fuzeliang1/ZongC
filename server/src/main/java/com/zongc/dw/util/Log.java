package com.zongc.dw.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** 极简控制台日志。 */
public final class Log {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static volatile boolean debug = false;

    private Log() {}

    public static void setDebug(boolean on) { debug = on; }

    public static void info(String msg) { print("INFO ", msg); }
    public static void warn(String msg) { print("WARN ", msg); }
    public static void error(String msg, Throwable t) {
        print("ERROR", msg + (t != null ? " : " + t : ""));
        if (t != null && debug) t.printStackTrace();
    }
    public static void debug(String msg) { if (debug) print("DEBUG", msg); }

    private static synchronized void print(String level, String msg) {
        System.out.println(LocalTime.now().format(FMT) + " " + level + " [" + Thread.currentThread().getName() + "] " + msg);
    }
}
