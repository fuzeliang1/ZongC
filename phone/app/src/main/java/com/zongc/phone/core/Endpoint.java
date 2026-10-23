package com.zongc.phone.core;

/**
 * 服务端地址。用户一般只输入 IP（例如 192.168.1.23），端口用默认值：
 * 设备接入 TCP 9000，网页 / HTTP 8080。
 *
 * <p>也接受 "IP:端口"（指定 TCP 端口）以及从浏览器地址栏复制来的 "http://IP:8080/"（指定 HTTP 端口）。</p>
 */
public final class Endpoint {
    public static final int DEFAULT_TCP = 9000;
    public static final int DEFAULT_HTTP = 8080;

    public final String host;
    public final int tcpPort;
    public final int httpPort;

    public Endpoint(String host, int tcpPort, int httpPort) {
        this.host = host;
        this.tcpPort = tcpPort;
        this.httpPort = httpPort;
    }

    public static Endpoint parse(String input) {
        return parse(input, DEFAULT_TCP, DEFAULT_HTTP);
    }

    /** 解析用户输入的地址；格式不对时抛出 IllegalArgumentException（消息可直接显示给用户）。 */
    public static Endpoint parse(String input, int defaultTcp, int defaultHttp) {
        if (input == null) throw new IllegalArgumentException("请输入服务端 IP 地址");
        String s = input.trim();
        boolean http = false;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://")) {
            s = s.substring(7);
            http = true;
        } else if (lower.startsWith("https://")) {
            s = s.substring(8);
            http = true;
        } else if (lower.startsWith("tcp://")) {
            s = s.substring(6);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);

        String host = s;
        String portText = null;
        if (s.startsWith("[")) {                       // [IPv6]:port
            int end = s.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("IPv6 地址缺少 ']'");
            host = s.substring(1, end);
            if (end + 1 < s.length()) {
                if (s.charAt(end + 1) != ':') throw new IllegalArgumentException("地址格式不对");
                portText = s.substring(end + 2);
            }
        } else {
            int first = s.indexOf(':');
            if (first >= 0 && first == s.lastIndexOf(':')) { // 只有一个冒号：host:port
                host = s.substring(0, first);
                portText = s.substring(first + 1);
            }
        }
        host = host.trim();
        if (host.isEmpty()) throw new IllegalArgumentException("请输入服务端 IP 地址");
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\' || c == '@') {
                throw new IllegalArgumentException("地址里有非法字符：" + host);
            }
        }
        int tcp = defaultTcp, web = defaultHttp;
        if (portText != null) {
            int port;
            try {
                port = Integer.parseInt(portText.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("端口必须是数字：" + portText);
            }
            if (port <= 0 || port > 65535) throw new IllegalArgumentException("端口超出范围：" + port);
            if (http) web = port;
            else tcp = port;
        }
        return new Endpoint(host, tcp, web);
    }

    @Override
    public String toString() {
        return host + " (TCP " + tcpPort + ", HTTP " + httpPort + ")";
    }
}
