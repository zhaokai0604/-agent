package com.award.log.handler;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.award.log.service.PerformanceAnalysisService;
import jakarta.annotation.Resource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.award.log.service.StatisticsService;

/**
 * 性能监控WebSocket处理器
 * 用于实时推送系统性能数据
 */
@Slf4j
@Component
public class PerformanceWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketMessageBuffer messageBuffer;

    // 存储所有活跃的WebSocket会话
    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    public PerformanceWebSocketHandler(WebSocketMessageBuffer messageBuffer) {
        this.messageBuffer = messageBuffer;
    }
    // 定时任务执行器
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> periodicPushTask;

    @Resource
    private PerformanceAnalysisService performanceAnalysisService;

    /** 与本机实时监控同源：oshi + MXBean（与 {@code StatisticsController#/admin/statistics/performance} 一致） */
    @Resource
    private StatisticsService statisticsService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 连接建立时添加会话到列表
        sessions.add(session);
        log.info("WebSocket连接建立: {}", session.getId());

        // 如果是第一个连接，启动定时推送任务
        if (sessions.size() == 1) {
            startPeriodicDataPush();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // 连接关闭时从列表中移除会话
        sessions.remove(session);
        log.info("WebSocket连接关闭: {}, 状态: {}", session.getId(), status);

        if (sessions.isEmpty()) {
            stopPeriodicDataPush();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 处理客户端发送的消息
        String payload = message.getPayload();
        log.info("收到客户端消息: {}", payload);

        // 可以根据客户端消息类型执行不同的操作
        // 例如，客户端请求特定类型的性能数据
        if ("refresh".equals(payload)) {
            pushPerformanceData();
        } else if ("sync".equalsIgnoreCase(payload.trim())) {
            replayBufferedMessages(session);
        }
    }

    private void replayBufferedMessages(WebSocketSession session) {
        if (messageBuffer == null || !session.isOpen()) {
            return;
        }
        try {
            List<String> buffered = messageBuffer.snapshotJsonMessages();
            for (String json : buffered) {
                session.sendMessage(new TextMessage(json));
            }
            log.debug("已向会话 {} 重放 {} 条缓冲消息", session.getId(), buffered.size());
        } catch (IOException e) {
            log.warn("重放 WebSocket 消息失败: {}", e.getMessage());
        }
    }

    /**
     * 启动定时推送性能数据的任务
     */
    private void startPeriodicDataPush() {
        if (periodicPushTask != null && !periodicPushTask.isCancelled() && !periodicPushTask.isDone()) {
            return;
        }
        // 每5秒推送一次性能数据
        periodicPushTask = executorService.scheduleAtFixedRate(this::pushPerformanceData, 0, 5, TimeUnit.SECONDS);
    }

    private void stopPeriodicDataPush() {
        if (periodicPushTask != null) {
            periodicPushTask.cancel(false);
            periodicPushTask = null;
        }
    }

    @PreDestroy
    public void destroy() {
        stopPeriodicDataPush();
        executorService.shutdownNow();
    }

    /**
     * 推送性能数据到所有客户端
     */
    private void pushPerformanceData() {
        try {
            Map<String, Object> messageData = new HashMap<>();
            messageData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            Map<String, Object> perf = Collections.emptyMap();
            try {
                if (statisticsService != null) {
                    perf = statisticsService.getSystemPerformance(null);
                }
            } catch (Exception ex) {
                log.debug("推送前采集性能快照失败: {}", ex.getMessage());
            }
            messageData.put("cpuUsage", toDouble(perf.get("cpuUsage")));
            messageData.put("memoryUsage", toDouble(perf.get("memoryUsage")));
            messageData.put("diskUsage", toDouble(perf.get("diskUsage")));
            messageData.put("networkUsage", toDouble(perf.get("networkUsage")));
            messageData.put("channel", "performance");

            String jsonMessage = com.alibaba.fastjson.JSON.toJSONString(messageData);
            messageBuffer.record(messageData);
            TextMessage textMessage = new TextMessage(jsonMessage);

            // 推送给所有活跃的客户端
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }

            log.debug("推送性能数据成功，活跃连接数: {}", sessions.size());
        } catch (Exception e) {
            log.error("推送性能数据失败", e);
        }
    }

    /**
     * 主动巡检等子系统向已连接客户端推送结构化告警（与周期性 performance 消息共用连接）。
     */
    public void broadcastJson(Map<String, Object> envelope) {
        if (sessions.isEmpty()) {
            return;
        }
        try {
            messageBuffer.record(envelope);
            String json = com.alibaba.fastjson.JSON.toJSONString(envelope);
            TextMessage textMessage = new TextMessage(json);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        } catch (Exception e) {
            log.warn("WebSocket 广播失败: {}", e.getMessage());
        }
    }

    private static double toDouble(Object raw) {
        return raw instanceof Number ? ((Number) raw).doubleValue() : 0.0;
    }
}
