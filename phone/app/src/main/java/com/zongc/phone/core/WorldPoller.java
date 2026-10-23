package com.zongc.phone.core;

import java.io.IOException;

/**
 * 后台线程：先拉一次地图，然后每隔 periodMs 拉一次世界快照，供手机上的小地图显示出租车、订单和路障。
 *
 * <p>手机智能体收不到服务端的 world 广播（那只发给出租车设备），所以用 HTTP 轮询
 * /api/snapshot（约 6 KB/次，3 Hz 对局域网毫无压力）。</p>
 */
public final class WorldPoller {
    private final MapClient api;
    private final long periodMs;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile MapData map;
    private volatile WorldSnapshot snapshot;
    private volatile long snapshotAtMs;
    private volatile String error;
    private volatile long polls;

    public WorldPoller(MapClient api, long periodMs) {
        this.api = api;
        this.periodMs = Math.max(50, periodMs);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "world-poller");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
    }

    private void loop() {
        while (running) {
            long wait = periodMs;
            try {
                if (map == null) map = api.fetchMap();
                snapshot = api.fetchSnapshot();
                snapshotAtMs = System.currentTimeMillis();
                polls++;
                error = null;
            } catch (IOException e) {
                error = DwConnection.describe(e);
                wait = 1000;
            } catch (RuntimeException e) {
                error = "数据错误：" + e.getMessage();
                wait = 1000;
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** 地图；还没拉到时为 null。 */
    public MapData map() {
        return map;
    }

    /** 最新快照；还没拉到时为 null。 */
    public WorldSnapshot snapshot() {
        return snapshot;
    }

    /** 最新快照距今的毫秒数；还没有快照时为 -1。 */
    public long snapshotAgeMs() {
        return snapshot == null ? -1 : System.currentTimeMillis() - snapshotAtMs;
    }

    public String error() {
        return error;
    }

    public long polls() {
        return polls;
    }

    /** 叫车（阻塞，在后台线程调用）。 */
    public String hail(int fromX, int fromY, int toX, int toY) throws IOException {
        return api.hail(fromX, fromY, toX, toY);
    }
}
