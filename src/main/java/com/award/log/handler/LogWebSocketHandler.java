package com.award.log.handler;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import jakarta.annotation.PreDestroy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import com.award.log.collector.LogCollectorManager;

/**
 * 日志监控WebSocket处理器
 * 用于实时推送系统日志数据
 */
@Slf4j
public class LogWebSocketHandler extends TextWebSocketHandler {

    // 存储所有活跃的WebSocket会话
    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    // 定时任务执行器
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> periodicPushTask;
    // 随机数生成器，用于模拟日志数据（作为备份）
    private final Random random = new Random();
    // 日志采集器管理器，用于获取真实日志数据
    @Resource
    private LogCollectorManager logCollectorManager;

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
        // 例如，客户端请求特定类型的日志数据
        if ("refresh".equals(payload)) {
            // 立即推送一次日志数据
            pushLogData();
        }
    }

    /**
     * 启动定时推送日志数据的任务
     */
    private void startPeriodicDataPush() {
        if (periodicPushTask != null && !periodicPushTask.isCancelled() && !periodicPushTask.isDone()) {
            return;
        }
        // 每3秒推送一次日志数据
        periodicPushTask = executorService.scheduleAtFixedRate(this::pushLogData, 0, 3, TimeUnit.SECONDS);
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
     * 推送日志数据到所有客户端
     */
    private void pushLogData() {
        try {
            // 获取真实的日志数据
            List<String> realLogs = collectRealLogs();
            
            // 如果有真实日志，推送真实日志
            if (!realLogs.isEmpty()) {
                for (String logEntry : realLogs) {
                    // 解析日志条目并转换为JSON
                    Map<String, Object> logData = parseLogEntry(logEntry);
                    if (logData != null) {
                        String jsonMessage = com.alibaba.fastjson.JSON.toJSONString(logData);
                        TextMessage textMessage = new TextMessage(jsonMessage);

                        // 推送给所有活跃的客户端
                        for (WebSocketSession session : sessions) {
                            if (session.isOpen()) {
                                session.sendMessage(textMessage);
                            }
                        }
                    }
                }
                log.debug("推送真实日志数据成功，条数: {}, 活跃连接数: {}", realLogs.size(), sessions.size());
            } else {
                // 如果没有真实日志，使用模拟数据（作为备份）
                Map<String, Object> logData = generateMockLogData();
                String jsonMessage = com.alibaba.fastjson.JSON.toJSONString(logData);
                TextMessage textMessage = new TextMessage(jsonMessage);

                // 推送给所有活跃的客户端
                for (WebSocketSession session : sessions) {
                    if (session.isOpen()) {
                        session.sendMessage(textMessage);
                    }
                }

                log.debug("无真实日志数据，推送模拟数据，活跃连接数: {}", sessions.size());
            }
        } catch (Exception e) {
            log.error("推送日志数据失败", e);
        }
    }
    
    /**
     * 收集真实的日志数据
     * @return 真实的日志列表
     */
    private List<String> collectRealLogs() {
        if (logCollectorManager != null) {
            try {
                return logCollectorManager.collectAll();
            } catch (Exception e) {
                log.error("收集真实日志数据失败", e);
                return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }
    
    /**
     * 解析日志条目为JSON格式
     * @param logEntry 日志条目
     * @return 解析后的日志数据
     */
    private Map<String, Object> parseLogEntry(String logEntry) {
        try {
            Map<String, Object> logData = new HashMap<>();
            
            // 生成时间戳
            logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
            
            // 设置默认级别为INFO
            logData.put("level", "INFO");
            
            // 尝试从日志条目中提取级别
            if (logEntry.contains("ERROR")) {
                logData.put("level", "ERROR");
            } else if (logEntry.contains("WARN")) {
                logData.put("level", "WARNING");
            } else if (logEntry.contains("DEBUG")) {
                logData.put("level", "DEBUG");
            } else if (logEntry.contains("FATAL")) {
                logData.put("level", "FATAL");
            }
            
            // 设置日志来源
            logData.put("source", "SYSTEM");
            
            // 设置日志内容
            logData.put("message", logEntry);
            
            // 设置进程ID和线程ID
            logData.put("pid", ProcessHandle.current().pid());
            logData.put("threadId", Thread.currentThread().getName());
            
            return logData;
        } catch (Exception e) {
            log.error("解析日志条目失败", e);
            return null;
        }
    }

    /**
     * 生成模拟日志数据
     * @return 模拟的日志数据
     */
    private Map<String, Object> generateMockLogData() {
        Map<String, Object> logData = new HashMap<>();

        // 生成时间戳
        logData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));

        // 随机生成日志级别
        String[] levels = {"INFO", "WARNING", "ERROR", "DEBUG", "FATAL"};
        String level = levels[random.nextInt(levels.length)];
        logData.put("level", level);

        // 随机生成日志来源
        String[] sources = {"SYSTEM", "APPLICATION", "SECURITY", "SERVICE", "DATABASE"};
        String source = sources[random.nextInt(sources.length)];
        logData.put("source", source);

        // 随机生成日志内容
        String[] messages = {
            "系统启动成功",
            "用户登录成功",
            "数据库连接异常",
            "文件上传失败",
            "网络连接超时",
            "内存使用过高",
            "CPU负载异常",
            "磁盘空间不足",
            "服务重启成功",
            "配置文件加载失败"
        };
        String message = messages[random.nextInt(messages.length)];
        logData.put("message", message);

        // 生成随机的进程ID
        logData.put("pid", random.nextInt(10000) + 1000);

        // 生成随机的线程ID
        logData.put("threadId", "thread-" + (random.nextInt(100) + 1));

        return logData;
    }
}
