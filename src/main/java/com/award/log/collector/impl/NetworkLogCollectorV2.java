package com.award.log.collector.impl;

import com.award.log.collector.LogCollector;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网络日志采集器V2
 * 支持Suricata和NetFlow网络日志采集
 */
@Slf4j
public class NetworkLogCollectorV2 implements LogCollector {

    private final int port;
    private final String protocol;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket socket;
    private Thread collectorThread;
    private final List<String> logs = new ArrayList<>();

    public NetworkLogCollectorV2(int port, String protocol) {
        this.port = port;
        this.protocol = protocol;
    }

    @Override
    public void start() {
        if (running.get()) {
            log.warn("[网络日志采集器] 已经在运行中");
            return;
        }

        running.set(true);
        collectorThread = new Thread(this::collectNetworkLogs);
        collectorThread.start();
        log.info("[网络日志采集器] 启动成功，监听端口: {}, 协议: {}", port, protocol);
    }

    @Override
    public void stop() {
        if (!running.get()) {
            log.warn("[网络日志采集器] 已经停止");
            return;
        }

        running.set(false);
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (collectorThread != null && collectorThread.isAlive()) {
            collectorThread.interrupt();
        }
        log.info("[网络日志采集器] 停止成功");
    }

    @Override
    public List<String> collect() {
        synchronized (logs) {
            List<String> result = new ArrayList<>(logs);
            logs.clear();
            return result;
        }
    }

    @Override
    public String getName() {
        return "NetworkLogCollectorV2";
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void collectNetworkLogs() {
        try {
            socket = new DatagramSocket(new InetSocketAddress(port));
            byte[] buffer = new byte[65535];

            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String log = new String(packet.getData(), 0, packet.getLength());
                
                // 区分Suricata和NetFlow日志
                if (log.contains("Suricata")) {
                    processSuricataLog(log);
                } else if (log.contains("NetFlow")) {
                    processNetFlowLog(log);
                } else {
                    // 其他网络日志
                    synchronized (logs) {
                        logs.add("[NETWORK] " + log);
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("[网络日志采集器] 异常: {}", e.getMessage());
            }
        }
    }

    private void processSuricataLog(String log) {
        // 处理Suricata日志
        synchronized (logs) {
            logs.add("[SURICATA] " + log);
        }
    }

    private void processNetFlowLog(String log) {
        // 处理NetFlow日志
        synchronized (logs) {
            logs.add("[NETFLOW] " + log);
        }
    }
}
