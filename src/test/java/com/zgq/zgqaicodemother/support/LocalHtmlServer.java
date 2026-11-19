package com.zgq.zgqaicodemother.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 测试用本地网页服务器：返回固定 HTML，避免截图测试依赖外部网络。
 */
public final class LocalHtmlServer implements AutoCloseable {

    private final HttpServer server;

    private LocalHtmlServer(HttpServer server) {
        this.server = server;
    }

    /**
     * 在随机空闲端口启动服务器。
     *
     * @param html 页面内容
     * @return 已启动的服务器
     */
    public static LocalHtmlServer start(String html) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();
        return new LocalHtmlServer(server);
    }

    /**
     * 返回可供浏览器访问的地址。
     */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
