package com.zongc.dw.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 运行事件日志（环形缓冲），在前端"事件"面板显示，也会打印到控制台。 */
public final class EventLog {
    public record Entry(long seq, double t, String level, String msg) {}

    private final ArrayDeque<Entry> buf = new ArrayDeque<>();
    private final int capacity;
    private long seq;
    private boolean echo = true;

    public EventLog(int capacity) {
        this.capacity = capacity;
    }

    public void setEcho(boolean echo) {
        this.echo = echo;
    }

    public synchronized void add(double t, String level, String msg) {
        Entry e = new Entry(++seq, Math.round(t * 10) / 10.0, level, msg);
        buf.addLast(e);
        while (buf.size() > capacity) buf.removeFirst();
        if (echo) com.zongc.dw.util.Log.info(String.format("[t=%.1f] %s", t, msg));
    }

    public void info(double t, String msg) { add(t, "info", msg); }
    public void warn(double t, String msg) { add(t, "warn", msg); }

    /** 最近 n 条，按时间先后排列。 */
    public synchronized List<Entry> recent(int n) {
        List<Entry> out = new ArrayList<>(Math.min(n, buf.size()));
        Iterator<Entry> it = buf.descendingIterator();
        while (it.hasNext() && out.size() < n) out.add(it.next());
        java.util.Collections.reverse(out);
        return out;
    }
}
