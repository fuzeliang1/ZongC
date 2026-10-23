package com.zongc.phone.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 测试用的极简 HTTP 服务端（只用 java.net，安卓单元测试环境里也能编译）。
 * 每个连接处理一个请求，响应后关闭。
 */
final class TinyHttpServer {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 一个请求的处理结果。 */
    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    interface Handler {
        Response handle(String method, String path, String body);
    }

    private final ServerSocket ss;
    private final Map<String, Handler> routes = new HashMap<String, Handler>();

    TinyHttpServer() throws IOException {
        ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        Thread h = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                serve(s);
                            }
                        }, "tiny-http-conn");
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        }, "tiny-http");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return ss.getLocalPort();
    }

    synchronized void route(String path, Handler h) {
        routes.put(path, h);
    }

    void stop() throws IOException {
        ss.close();
    }

    private void serve(Socket s) {
        try {
            InputStream in = s.getInputStream();
            String head = readHead(in);
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            String method = first[0], path = first.length > 1 ? first[1] : "/";
            int length = 0;
            for (String l : lines) {
                int colon = l.indexOf(':');
                if (colon > 0 && l.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-length")) {
                    length = Integer.parseInt(l.substring(colon + 1).trim());
                }
            }
            byte[] body = new byte[length];
            int off = 0;
            while (off < length) {
                int n = in.read(body, off, length - off);
                if (n < 0) break;
                off += n;
            }
            Handler h;
            synchronized (this) {
                h = routes.get(path);
            }
            Response r = h == null ? new Response(404, "{\"ok\":false,\"error\":\"没有这个接口\"}")
                    : h.handle(method, path, new String(body, 0, off, UTF8));
            byte[] out = r.body.getBytes(UTF8);
            String header = "HTTP/1.1 " + r.code + " X\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + out.length + "\r\nConnection: close\r\n\r\n";
            OutputStream os = s.getOutputStream();
            os.write(header.getBytes(UTF8));
            os.write(out);
            os.flush();
        } catch (IOException ignored) {
            // 客户端提前断开
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // 已关闭
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("请求不完整");
            buf.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = b == '\r' ? 1 : 0;
        }
        return new String(buf.toByteArray(), UTF8);
    }
}
