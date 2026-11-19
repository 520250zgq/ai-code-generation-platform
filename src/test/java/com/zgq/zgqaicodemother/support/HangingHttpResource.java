package com.zgq.zgqaicodemother.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用“挂起资源”服务器：接受连接后不返回任何响应，用于模拟第三方图片永久加载的场景。
 */
public final class HangingHttpResource implements AutoCloseable {

    private final ServerSocket serverSocket;

    private final List<Socket> acceptedSockets = Collections.synchronizedList(new ArrayList<>());

    private HangingHttpResource(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    /**
     * 在随机端口启动服务器，后台虚拟线程持续接受连接但保持不响应。
     */
    public static HangingHttpResource start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        HangingHttpResource resource = new HangingHttpResource(serverSocket);
        Thread.ofVirtual()
                .name("hanging-http-resource")
                .start(() -> {
                    try {
                        while (!serverSocket.isClosed()) {
                            Socket socket = serverSocket.accept();
                            // 保持连接打开，浏览器会一直等待该资源。
                            resource.acceptedSockets.add(socket);
                        }
                    } catch (IOException ignored) {
                        // 测试结束时关闭 ServerSocket 会中断 accept，属于正常退出。
                    }
                });
        return resource;
    }

    /**
     * 返回一个永远不会完成的资源地址。
     */
    public String url() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/slow.png";
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 忽略关闭异常。
        }
        synchronized (acceptedSockets) {
            for (Socket socket : acceptedSockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 忽略关闭异常。
                }
            }
            acceptedSockets.clear();
        }
    }
}
