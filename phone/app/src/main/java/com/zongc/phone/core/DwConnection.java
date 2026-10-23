package com.zongc.phone.core;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * 与数字世界的设备接入口（TCP 9000，每行一条 JSON）保持连接。
 *
 * <pre>
 *   连接 → register{kind:"phone"} → welcome{agentId:"phone-1"}
 *        → 每 0.1 s 发 state{x,y,theta,v,w}（地图坐标）
 *        → 每 1 s 发 ping，收 pong 计算往返时延（RTT）
 *   断线 / 5 s 收不到任何消息 → 关闭连接，1、2、3、5 s 后自动重连
 * </pre>
 *
 * <p>服务端 3 s 收不到消息会判定心跳超时，把手机智能体移除；重连后会重新出现
 * （编号被占用时服务端会分配新的 phone-N）。</p>
 */
public final class DwConnection {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int[] BACKOFF_MS = {1000, 2000, 3000, 5000};

    /** 这么久收不到服务端任何消息（pong 每秒一条）就认为连接已死。测试时可以调小。 */
    long silenceLimitNs = 5_000_000_000L;

    /** 提供当前位姿（地图坐标）。 */
    public interface PoseSource {
        /** @return {x, y, theta, v, w}；返回 null 表示这一拍不上报 */
        double[] pose();
    }

    public enum State {
        /** 未启动或已停止。 */
        STOPPED,
        /** 正在建立 TCP 连接、等待 welcome。 */
        CONNECTING,
        /** 已注册，正在上报。 */
        CONNECTED,
        /** 连接失败，等待重连。 */
        WAITING
    }

    /** 连接状态快照（界面每 0.1 s 读一次）。 */
    public static final class Status {
        public State state = State.STOPPED;
        public String agentId;
        /** 最近一次错误（中文，可直接显示）；正常时为 null。 */
        public String error;
        /** 服务端发来的最近一条 error 消息。 */
        public String serverError;
        public double rttMs = Double.NaN;
        public long sent;
        public long received;
        public int connects;
        public String mapName;
        /** 已连续连接的秒数。 */
        public double connectedSec;
    }

    private final String host;
    private final int port;
    private final String wantedId;
    private final String deviceName;
    private final PoseSource source;
    private final double stateHz;

    private final Object lock = new Object();
    private final Object writeLock = new Object();
    private final ArrayDeque<Long> pingTimes = new ArrayDeque<Long>();
    private volatile boolean running;
    private Thread loop;

    // ---- 以下字段由 lock 保护
    private Socket socket;
    private OutputStream out;
    private State state = State.STOPPED;
    private String agentId;
    private String error;
    private String serverError;
    private double rttMs = Double.NaN;
    private long sent;
    private long received;
    private int connects;
    private String mapName;
    private long connectedAt;
    private long lastRx;

    /**
     * @param wantedId   希望使用的编号（如 "phone-1"），被占用时服务端会另分配
     * @param deviceName 手机型号等，只用于服务端日志
     * @param stateHz    上报频率（Hz），建议 10
     */
    public DwConnection(String host, int port, String wantedId, String deviceName, PoseSource source, double stateHz) {
        this.host = host;
        this.port = port;
        this.wantedId = wantedId;
        this.deviceName = deviceName;
        this.source = source;
        this.stateHz = stateHz > 0 ? stateHz : 10;
    }

    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            state = State.CONNECTING;
        }
        loop = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "dw-connection");
        loop.setDaemon(true);
        loop.start();
    }

    /** 断开并停止重连（可以在任何线程调用，不会阻塞）。 */
    public void stop() {
        running = false;
        closeSocket();
        Thread t = loop;
        if (t != null) t.interrupt();
    }

    public boolean isRunning() {
        return running;
    }

    public Status status() {
        Status s = new Status();
        synchronized (lock) {
            s.state = state;
            s.agentId = agentId;
            s.error = error;
            s.serverError = serverError;
            s.rttMs = rttMs;
            s.sent = sent;
            s.received = received;
            s.connects = connects;
            s.mapName = mapName;
            s.connectedSec = state == State.CONNECTED ? (System.nanoTime() - connectedAt) * 1e-9 : 0;
        }
        return s;
    }

    /** 发送一条设备事件（服务端会记入事件日志），未连接时忽略。 */
    public void sendEvent(String text) {
        try {
            sendLine(new Json.Obj().put("type", "event").put("event", text).toString());
        } catch (IOException ignored) {
            // 连接断了：重连循环会处理
        }
    }

    // ================================================================ 内部

    private void runLoop() {
        int attempt = 0;
        while (running) {
            setState(State.CONNECTING, null);
            long start = System.nanoTime();
            String err = null;
            try {
                session();
            } catch (IOException e) {
                err = describe(e);
            } catch (RuntimeException e) {
                err = "内部错误：" + e;
            }
            closeSocket();
            if (!running) break;
            boolean wasLong = System.nanoTime() - start > 10_000_000_000L;
            if (wasLong) attempt = 0;
            int wait = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
            attempt++;
            setState(State.WAITING, err != null ? err : "连接已断开");
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }
        setState(State.STOPPED, null);
    }

    private void session() throws IOException {
        Socket s = new Socket();
        synchronized (lock) {
            if (!running) return;
            socket = s;
        }
        s.connect(new InetSocketAddress(host, port), 3000);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        final BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
        synchronized (lock) {
            out = new BufferedOutputStream(s.getOutputStream());
            pingTimes.clear();
            lastRx = System.nanoTime();
            connects++;
        }
        sendLine(registerMessage());

        final String[] readerError = new String[1];
        final boolean[] alive = {true};
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    while ((line = in.readLine()) != null) onLine(line);
                    readerError[0] = "服务端关闭了连接";
                } catch (IOException e) {
                    readerError[0] = describe(e);
                } finally {
                    synchronized (alive) {
                        alive[0] = false;
                    }
                    closeSocket();
                }
            }
        }, "dw-reader");
        reader.setDaemon(true);
        reader.start();

        long period = (long) (1e9 / stateHz);
        long nextState = System.nanoTime();
        long nextPing = nextState + 500_000_000L;
        try {
            while (running) {
                synchronized (alive) {
                    if (!alive[0]) break;
                }
                long now = System.nanoTime();
                if (now >= nextState) {
                    double[] p = source.pose();
                    if (p != null && p.length >= 3) sendLine(stateMessage(p));
                    nextState += period;
                    if (now - nextState > 1_000_000_000L) nextState = now + period;   // 落后太多：不补发
                }
                if (now >= nextPing) {
                    synchronized (lock) {
                        if (pingTimes.size() < 32) pingTimes.addLast(now);
                    }
                    sendLine("{\"type\":\"ping\"}");
                    nextPing = now + 1_000_000_000L;
                }
                long silent;
                synchronized (lock) {
                    silent = now - lastRx;
                }
                if (silent > silenceLimitNs) {
                    throw new IOException("服务端 " + Math.round(silenceLimitNs / 1e9) + " 秒没有响应");
                }
                long sleepNs = Math.min(nextState, nextPing) - System.nanoTime();
                if (sleepNs > 0) {
                    try {
                        Thread.sleep(Math.max(1, sleepNs / 1_000_000L));
                    } catch (InterruptedException e) {
                        if (!running) return;
                    }
                }
            }
        } catch (IOException e) {
            // 读线程先发现断线（它会先记下原因、再关闭连接）时，用它的错误信息，更准确
            boolean readerDead;
            synchronized (alive) {
                readerDead = !alive[0];
            }
            if (readerDead && readerError[0] != null) throw new IOException(readerError[0]);
            throw e;
        }
        if (running && readerError[0] != null) throw new IOException(readerError[0]);
    }

    private void onLine(String line) {
        long now = System.nanoTime();
        Map<String, Object> m;
        try {
            m = Json.parseObject(line);
        } catch (IllegalArgumentException e) {
            m = null;
        }
        synchronized (lock) {
            lastRx = now;
            received++;
            if (m == null) return;
            String type = Json.str(m, "type");
            if ("welcome".equals(type)) {
                agentId = Json.str(m, "agentId");
                Map<String, Object> map = Json.obj(m.get("map"));
                mapName = Json.str(map, "name");
                state = State.CONNECTED;
                connectedAt = now;
                error = null;
                serverError = null;
            } else if ("pong".equals(type)) {
                Long sentAt = pingTimes.pollFirst();      // TCP 保序，服务端按顺序回复：先进先出即可配对
                if (sentAt != null) {
                    double rtt = (now - sentAt) / 1e6;
                    rttMs = Double.isNaN(rttMs) ? rtt : rttMs + 0.3 * (rtt - rttMs);
                }
            } else if ("error".equals(type)) {
                serverError = Json.str(m, "message");
            }
        }
    }

    private String registerMessage() {
        Json.Obj o = new Json.Obj()
                .put("type", "register")
                .put("protocol", 1)
                .put("kind", "phone")
                .put("role", "free")
                .put("agentId", wantedId)
                .put("name", deviceName);
        double[] p = source.pose();
        if (p != null && p.length >= 3) {
            o.put("x", p[0], 3).put("y", p[1], 3).put("theta", p[2], 4);
        }
        return o.toString();
    }

    private String stateMessage(double[] p) {
        String id;
        synchronized (lock) {
            id = agentId;
        }
        Json.Obj o = new Json.Obj().put("type", "state");
        if (id != null) o.put("agentId", id);
        o.put("x", p[0], 3).put("y", p[1], 3).put("theta", p[2], 4);
        o.put("v", p.length > 3 ? p[3] : 0, 3);
        o.put("w", p.length > 4 ? p[4] : 0, 3);
        return o.toString();
    }

    private void sendLine(String json) throws IOException {
        OutputStream os;
        synchronized (lock) {
            os = out;
        }
        if (os == null) throw new IOException("未连接");
        byte[] bytes = (json + "\n").getBytes(UTF8);
        synchronized (writeLock) {
            os.write(bytes);
            os.flush();
        }
        synchronized (lock) {
            sent++;
        }
    }

    private void closeSocket() {
        Socket s;
        synchronized (lock) {
            s = socket;
            socket = null;
            out = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 已经关闭
            }
        }
    }

    /** 切换状态；err 不为 null 时同时记下错误（重连期间保留最近一次错误供界面显示）。 */
    private void setState(State st, String err) {
        synchronized (lock) {
            state = st;
            if (err != null) error = err;
            if (st == State.STOPPED) {
                error = null;
                agentId = null;
            }
        }
    }

    /** 把网络异常翻译成中文提示。 */
    static String describe(IOException e) {
        if (e instanceof ConnectException) return "连接被拒绝：服务端没启动，或 IP / 端口不对";
        if (e instanceof SocketTimeoutException) return "连接超时：确认手机和电脑在同一局域网、电脑防火墙已放行 9000 端口";
        if (e instanceof UnknownHostException) return "找不到主机：" + e.getMessage();
        if (e instanceof NoRouteToHostException) return "网络不可达：检查 Wi-Fi 是否连接";
        String msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }
}
