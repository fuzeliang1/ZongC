package com.zongc.phone.core;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DwConnectionTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 最小的假服务端：回 welcome、回 pong，记录收到的每一行。 */
    static final class FakeServer {
        final ServerSocket ss;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<String>();
        final AtomicInteger accepts = new AtomicInteger();
        volatile Socket current;
        volatile boolean replyPong = true;
        volatile boolean replyWelcome = true;

        FakeServer() throws IOException {
            ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "fake-server");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return ss.getLocalPort();
        }

        private void acceptLoop() {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    accepts.incrementAndGet();
                    current = s;
                    Thread h = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handle(s);
                        }
                    }, "fake-session");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void handle(Socket s) {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
                OutputStream out = s.getOutputStream();
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                    String type = Json.str(Json.parseObject(line), "type");
                    if ("register".equals(type) && replyWelcome) {
                        out.write(("{\"type\":\"welcome\",\"protocol\":1,\"agentId\":\"phone-7\",\"role\":\"free\","
                                + "\"map\":{\"name\":\"MiniCity 5x4\",\"width\":53,\"height\":34,\"resolution\":1.0}}\n")
                                .getBytes(UTF8));
                    } else if ("ping".equals(type) && replyPong) {
                        out.write("{\"type\":\"pong\",\"t\":12.5}\n".getBytes(UTF8));
                    }
                    out.flush();
                }
            } catch (IOException ignored) {
                // 客户端断开
            }
        }

        void dropClient() throws IOException {
            current.close();
        }

        void close() throws IOException {
            ss.close();
            Socket s = current;
            if (s != null) s.close();
        }
    }

    private FakeServer server;
    private DwConnection conn;

    @After
    public void tearDown() throws IOException {
        if (conn != null) conn.stop();
        if (server != null) server.close();
    }

    private static final DwConnection.PoseSource POSE = new DwConnection.PoseSource() {
        @Override
        public double[] pose() {
            return new double[]{12.5, 3.25, 1.5708, 0.8, 0.1};
        }
    };

    private static boolean waitFor(DwConnection c, DwConnection.State st, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.status().state == st) return true;
            Thread.sleep(10);
        }
        return false;
    }

    private List<Map<String, Object>> drain(String type) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String l : server.lines) {
            Map<String, Object> m = Json.parseObject(l);
            if (type.equals(Json.str(m, "type"))) out.add(m);
        }
        return out;
    }

    @Test
    public void registersAsPhoneAndStreamsStateAtTenHertz() throws Exception {
        server = new FakeServer();
        conn = new DwConnection("127.0.0.1", server.port(), "phone-1", "单元测试", POSE, 10);
        conn.start();
        String first = server.lines.poll(3, TimeUnit.SECONDS);
        assertNotNull("应收到 register", first);
        Map<String, Object> reg = Json.parseObject(first);
        assertEquals("register", Json.str(reg, "type"));
        assertEquals("phone", Json.str(reg, "kind"));
        assertEquals("free", Json.str(reg, "role"));
        assertEquals("phone-1", Json.str(reg, "agentId"));
        assertEquals("单元测试", Json.str(reg, "name"));
        assertEquals(12.5, Json.num(reg, "x", 0), 1e-9);
        assertEquals(3.25, Json.num(reg, "y", 0), 1e-9);

        assertTrue(waitFor(conn, DwConnection.State.CONNECTED, 2000));
        assertEquals("phone-7", conn.status().agentId);
        assertEquals("MiniCity 5x4", conn.status().mapName);

        Thread.sleep(2000);
        List<Map<String, Object>> states = drain("state");
        assertTrue("2 秒应有约 20 条 state，实际 " + states.size(), states.size() >= 16 && states.size() <= 24);
        Map<String, Object> last = states.get(states.size() - 1);
        assertEquals("phone-7", Json.str(last, "agentId"));
        assertEquals(12.5, Json.num(last, "x", 0), 1e-9);
        assertEquals(1.5708, Json.num(last, "theta", 0), 1e-9);
        assertEquals(0.8, Json.num(last, "v", 0), 1e-9);
        assertTrue(drain("ping").size() >= 2);
        DwConnection.Status st = conn.status();
        assertFalse("应测出往返时延", Double.isNaN(st.rttMs));
        assertTrue(st.rttMs < 200);
        assertTrue(st.sent >= 18);

        conn.sendEvent("叫车 T001");
        Thread.sleep(200);
        assertEquals("叫车 T001", Json.str(drain("event").get(0), "event"));

        conn.stop();
        assertTrue(waitFor(conn, DwConnection.State.STOPPED, 2000));
        assertEquals(null, conn.status().agentId);
    }

    @Test
    public void reconnectsAfterTheServerDropsTheConnection() throws Exception {
        server = new FakeServer();
        conn = new DwConnection("127.0.0.1", server.port(), "phone-1", "t", POSE, 10);
        conn.start();
        assertTrue(waitFor(conn, DwConnection.State.CONNECTED, 2000));
        server.dropClient();
        assertTrue(waitFor(conn, DwConnection.State.WAITING, 2000));
        assertNotNull(conn.status().error);
        assertTrue(waitFor(conn, DwConnection.State.CONNECTED, 4000));
        assertEquals(2, server.accepts.get());
        assertEquals(2, conn.status().connects);
    }

    @Test
    public void silentServerIsDetected() throws Exception {
        server = new FakeServer();
        server.replyPong = false;
        conn = new DwConnection("127.0.0.1", server.port(), "phone-1", "t", POSE, 10);
        conn.silenceLimitNs = 1_500_000_000L;
        conn.start();
        assertTrue(waitFor(conn, DwConnection.State.CONNECTED, 2000));
        assertTrue(waitFor(conn, DwConnection.State.WAITING, 3000));
        assertTrue(conn.status().error, conn.status().error.contains("没有响应"));
    }

    @Test
    public void refusedConnectionGivesAFriendlyMessage() throws Exception {
        int port;
        ServerSocket tmp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        port = tmp.getLocalPort();
        tmp.close();                                       // 端口上没有服务
        conn = new DwConnection("127.0.0.1", port, "phone-1", "t", POSE, 10);
        conn.start();
        assertTrue(waitFor(conn, DwConnection.State.WAITING, 2000));
        assertTrue(conn.status().error, conn.status().error.startsWith("连接被拒绝"));
    }
}
