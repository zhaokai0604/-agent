package com.award.log.collector.impl;

import com.award.log.collector.LogCollector;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网络日志采集器（TCP/UDP Syslog 等）。
 * <p>
 * <b>防 OOM：</b>
 * <ul>
 *   <li>采集缓冲有硬上限 {@link #DEFAULT_BUFFER_MAX_LINES}，满则丢最旧</li>
 *   <li>TCP 受理用有界线程池（最多 {@link #MAX_TCP_THREADS}），拒绝时关连接，禁止 CachedThreadPool</li>
 * </ul>
 */
@Slf4j
public class NetworkLogCollector implements LogCollector {

    private static final int DEFAULT_BUFFER_MAX_LINES = 8000;
    private static final int MAX_TCP_THREADS = 16;

    private final int port;
    private final String protocol;
    private final String name;
    private final int bufferMaxLines;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong droppedLines = new AtomicLong();
    private final ThreadPoolExecutor executorService;
    private final Deque<String> collectedLogs;
    private ServerSocket tcpServerSocket;
    private DatagramSocket udpServerSocket;

    public NetworkLogCollector(int port, String protocol, String name) {
        this.port = port;
        this.protocol = protocol.toUpperCase();
        this.name = name;
        this.bufferMaxLines = DEFAULT_BUFFER_MAX_LINES;
        this.executorService = new ThreadPoolExecutor(
                1,
                MAX_TCP_THREADS,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "net-log-" + name);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.collectedLogs = new ArrayDeque<>();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[网络日志采集器] 开始采集，{}端口: {}", protocol, port);
            if ("TCP".equals(protocol)) {
                executorService.submit(this::startTcpServer);
            } else if ("UDP".equals(protocol)) {
                executorService.submit(this::startUdpServer);
            } else {
                log.error("[网络日志采集器] 不支持的协议: {}", protocol);
                running.set(false);
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("[网络日志采集器] 停止采集，{}端口: {}", protocol, port);
            if (tcpServerSocket != null && !tcpServerSocket.isClosed()) {
                try {
                    tcpServerSocket.close();
                } catch (IOException e) {
                    log.error("[网络日志采集器] 关闭TCP服务器异常", e);
                }
            }
            if (udpServerSocket != null && !udpServerSocket.isClosed()) {
                udpServerSocket.close();
            }
            executorService.shutdownNow();
        }
    }

    @Override
    public List<String> collect() {
        synchronized (collectedLogs) {
            List<String> logs = new ArrayList<>(collectedLogs);
            collectedLogs.clear();
            return logs;
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void startTcpServer() {
        try {
            tcpServerSocket = new ServerSocket(port);
            log.info("[网络日志采集器] TCP服务器已启动，监听端口: {}", port);

            while (running.get()) {
                try {
                    Socket clientSocket = tcpServerSocket.accept();
                    try {
                        executorService.submit(() -> handleTcpClient(clientSocket));
                    } catch (RejectedExecutionException e) {
                        log.warn("[网络日志采集器] TCP 连接过多，关闭新连接");
                        try {
                            clientSocket.close();
                        } catch (IOException ignored) {
                        }
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("[网络日志采集器] TCP服务器异常", e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("[网络日志采集器] 启动TCP服务器失败", e);
            running.set(false);
        }
    }

    private void handleTcpClient(Socket clientSocket) {
        try (clientSocket;
             var inputStream = clientSocket.getInputStream();
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream))) {

            String line;
            while ((line = reader.readLine()) != null && running.get()) {
                offerLog(line);
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("[网络日志采集器] 处理TCP客户端异常", e);
            }
        }
    }

    private void startUdpServer() {
        try {
            udpServerSocket = new DatagramSocket(port);
            log.info("[网络日志采集器] UDP服务器已启动，监听端口: {}", port);

            byte[] buffer = new byte[65536];
            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpServerSocket.receive(packet);
                    String logMessage = new String(packet.getData(), 0, packet.getLength());
                    offerLog(logMessage);
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("[网络日志采集器] UDP服务器异常", e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("[网络日志采集器] 启动UDP服务器失败", e);
            running.set(false);
        }
    }

    private void offerLog(String line) {
        synchronized (collectedLogs) {
            while (collectedLogs.size() >= bufferMaxLines) {
                collectedLogs.removeFirst();
                long dropped = droppedLines.incrementAndGet();
                if (dropped == 1 || dropped % 1000 == 0) {
                    log.warn("[网络日志采集器] 缓冲已满 ({}), 已丢弃 {} 行", bufferMaxLines, dropped);
                }
            }
            collectedLogs.addLast(line);
        }
    }
}
