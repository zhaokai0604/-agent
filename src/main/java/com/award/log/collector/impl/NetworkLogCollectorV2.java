package com.award.log.collector.impl;

import com.award.log.collector.LogCollector;
import com.award.log.security.signal.SecuritySignal;
import com.award.log.security.signal.SecuritySignalService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP 网络日志采集（V2）：解析 Suricata / NetFlow 等安全信号后入缓冲。
 * 缓冲上限 {@link #BUFFER_MAX_LINES}，满则丢最旧，防止 syslog 洪泛 OOM。
 */
@Slf4j
public class NetworkLogCollectorV2 implements LogCollector {

    private static final int BUFFER_MAX_LINES = 8000;

    private final int port;
    private final String protocol;
    private final SecuritySignalService securitySignalService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<String> logs = new ArrayList<>();

    private DatagramSocket socket;
    private Thread collectorThread;

    public NetworkLogCollectorV2(int port, String protocol, SecuritySignalService securitySignalService) {
        this.port = port;
        this.protocol = protocol;
        this.securitySignalService = securitySignalService;
    }

    @Override
    public void start() {
        if (running.get()) {
            log.warn("[NetworkLogCollectorV2] collector already running");
            return;
        }

        try {
            socket = new DatagramSocket(new InetSocketAddress(port));
            running.set(true);
            collectorThread = new Thread(this::collectNetworkLogs, "network-log-collector-v2");
            collectorThread.start();
            log.info("[NetworkLogCollectorV2] started on port {} protocol {}", port, protocol);
        } catch (IOException e) {
            running.set(false);
            log.error("[NetworkLogCollectorV2] failed to start on port {}: {}", port, e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (!running.get()) {
            log.warn("[NetworkLogCollectorV2] collector already stopped");
            return;
        }

        running.set(false);
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (collectorThread != null && collectorThread.isAlive()) {
            collectorThread.interrupt();
        }
        log.info("[NetworkLogCollectorV2] stopped");
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
            byte[] buffer = new byte[65535];

            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String log = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                if (log.contains("Suricata")) {
                    processSuricataLog(log);
                } else if (log.contains("NetFlow")) {
                    processNetFlowLog(log);
                } else {
                    offerLog("[NETWORK] " + log);
                }
            }
        } catch (IOException e) {
            boolean shouldReport = running.get();
            running.set(false);
            if (shouldReport) {
                log.error("[NetworkLogCollectorV2] collector error: {}", e.getMessage());
            }
        }
    }

    private void processSuricataLog(String log) {
        SecuritySignal signal = securitySignalService.ingest("suricata", log);
        String title = signal != null && signal.title() != null && !signal.title().isBlank()
                ? signal.title()
                : log;
        offerLog("[SURICATA] " + title);
    }

    private void processNetFlowLog(String log) {
        securitySignalService.ingest("netflow", log);
        offerLog("[NETFLOW] " + log);
    }

    private void offerLog(String line) {
        synchronized (logs) {
            while (logs.size() >= BUFFER_MAX_LINES) {
                logs.remove(0);
            }
            logs.add(line);
        }
    }
}
