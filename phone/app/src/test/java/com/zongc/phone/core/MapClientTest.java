package com.zongc.phone.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MapClientTest {
    private TinyHttpServer http;
    private volatile String lastBody;

    @Before
    public void setUp() throws IOException {
        final String map = MapDataTest.fixture("map.json");
        final String snap = MapDataTest.fixture("snapshot.json");
        http = new TinyHttpServer();
        http.route("/api/map", new TinyHttpServer.Handler() {
            @Override
            public TinyHttpServer.Response handle(String method, String path, String body) {
                return new TinyHttpServer.Response(200, map);
            }
        });
        http.route("/api/snapshot", new TinyHttpServer.Handler() {
            @Override
            public TinyHttpServer.Response handle(String method, String path, String body) {
                return new TinyHttpServer.Response(200, snap);
            }
        });
        http.route("/api/tasks", new TinyHttpServer.Handler() {
            @Override
            public TinyHttpServer.Response handle(String method, String path, String body) {
                lastBody = body;
                if (!"POST".equals(method)) return new TinyHttpServer.Response(405, "{\"ok\":false,\"error\":\"只支持 POST\"}");
                Map<String, Object> b = Json.parseObject(body);
                double[] from = Json.nums(b, "from"), to = Json.nums(b, "to");
                if (from[0] == to[0] && from[1] == to[1]) {
                    return new TinyHttpServer.Response(400, "{\"ok\":false,\"error\":\"上下车点不能相同\"}");
                }
                return new TinyHttpServer.Response(200, "{\"ok\":true,\"taskId\":\"T042\"}");
            }
        });
    }

    @After
    public void tearDown() throws IOException {
        http.stop();
    }

    private MapClient client() {
        return new MapClient("127.0.0.1", http.port());
    }

    @Test
    public void fetchesMapAndSnapshot() throws IOException {
        MapData m = client().fetchMap();
        assertEquals(53, m.width);
        WorldSnapshot s = client().fetchSnapshot();
        assertEquals(8, s.agents.size());
    }

    @Test
    public void hailPostsCellsAndReturnsTaskId() throws IOException {
        assertEquals("T042", client().hail(1, 5, 30, 24));
        Map<String, Object> b = Json.parseObject(lastBody);
        assertEquals(1, Json.nums(b, "from")[0], 0);
        assertEquals(24, Json.nums(b, "to")[1], 0);
    }

    @Test
    public void serverErrorMessageIsPropagated() {
        try {
            client().hail(1, 5, 1, 5);
            fail("应当抛出异常");
        } catch (IOException e) {
            assertEquals("上下车点不能相同", e.getMessage());
        }
    }

    @Test
    public void unknownPathGivesServerMessage() {
        try {
            client().request("GET", "/api/nothing", null);
            fail("应当抛出异常");
        } catch (IOException e) {
            assertEquals("没有这个接口", e.getMessage());
        }
    }

    @Test
    public void unreachableServerThrows() throws IOException {
        ServerSocket tmp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = tmp.getLocalPort();
        tmp.close();
        try {
            new MapClient("127.0.0.1", port, 1000).fetchMap();
            fail("应当抛出异常");
        } catch (IOException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void pollerFetchesMapThenSnapshots() throws Exception {
        WorldPoller p = new WorldPoller(client(), 50);
        assertEquals(-1, p.snapshotAgeMs());
        p.start();
        long end = System.currentTimeMillis() + 3000;
        while (p.polls() < 3 && System.currentTimeMillis() < end) Thread.sleep(20);
        p.stop();
        assertTrue(p.polls() >= 3);
        assertEquals(53, p.map().width);
        assertEquals(8, p.snapshot().agents.size());
        assertTrue(p.snapshotAgeMs() >= 0);
        assertEquals(null, p.error());
        assertEquals("T042", p.hail(1, 5, 2, 8));
    }

    @Test
    public void pollerReportsErrorsAndKeepsTrying() throws Exception {
        ServerSocket tmp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = tmp.getLocalPort();
        tmp.close();
        WorldPoller p = new WorldPoller(new MapClient("127.0.0.1", port, 500), 50);
        p.start();
        long end = System.currentTimeMillis() + 3000;
        while (p.error() == null && System.currentTimeMillis() < end) Thread.sleep(20);
        p.stop();
        assertTrue(p.error(), p.error().startsWith("连接被拒绝"));
        assertEquals(null, p.map());
    }

    @Test
    public void ipv6HostIsBracketed() {
        assertEquals("http://[fe80::1]:8080", new MapClient("fe80::1", 8080).baseUrl());
        assertEquals("http://10.0.0.2:8081", new MapClient("10.0.0.2", 8081).baseUrl());
    }
}
