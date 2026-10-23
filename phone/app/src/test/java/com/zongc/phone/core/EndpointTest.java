package com.zongc.phone.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class EndpointTest {

    @Test
    public void plainIpUsesDefaultPorts() {
        Endpoint e = Endpoint.parse(" 192.168.1.23 ");
        assertEquals("192.168.1.23", e.host);
        assertEquals(9000, e.tcpPort);
        assertEquals(8080, e.httpPort);
    }

    @Test
    public void portAfterColonIsTcpPort() {
        Endpoint e = Endpoint.parse("10.0.0.5:9100");
        assertEquals("10.0.0.5", e.host);
        assertEquals(9100, e.tcpPort);
        assertEquals(8080, e.httpPort);
    }

    @Test
    public void browserUrlSetsHttpPort() {
        Endpoint e = Endpoint.parse("http://192.168.0.8:8081/index.html");
        assertEquals("192.168.0.8", e.host);
        assertEquals(9000, e.tcpPort);
        assertEquals(8081, e.httpPort);
    }

    @Test
    public void ipv6AndHostnames() {
        assertEquals("fe80::1", Endpoint.parse("[fe80::1]:9000").host);
        assertEquals("fe80::1", Endpoint.parse("fe80::1").host);
        assertEquals("my-laptop.local", Endpoint.parse("my-laptop.local").host);
    }

    @Test
    public void rejectsGarbage() {
        String[] bad = {"", "   ", "1.2.3.4:abc", "1.2.3.4:70000", "a b", "http://", "[::1"};
        for (String s : bad) {
            try {
                Endpoint.parse(s);
                fail("应当拒绝: '" + s + "'");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }
}
