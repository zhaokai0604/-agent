package com.award.log.handler;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 性能监控WebSocket处理器
 * 用于实时推送系统性能数据
 */
@Slf4j
public class PerformanceWebSocketHandler extends TextWebSocketHandler {

    // 存储所有活跃的WebSocket会话
    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    // 定时任务执行器
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> periodicPushTask;

    // 性能分析服务
    @Resource
    private PerformanceAnalysisService performanceAnalysisService;

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
            // 立即推送一次性能数据
            pushPerformanceData();
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
            // 构建推送消息
            Map<String, Object> messageData = new HashMap<>();
            messageData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            // 设置默认值
            messageData.put("cpuUsage", 0.0);
            messageData.put("memoryUsage", 0.0);
            messageData.put("diskUsage", 0.0);
            messageData.put("networkUsage", 0.0);

            // 转换为JSON字符串
            String jsonMessage = com.alibaba.fastjson.JSON.toJSONString(messageData);
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
}
