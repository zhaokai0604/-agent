package com.award.log.websocket;

import com.award.log.analyzer.RealTimeLogAnalyzer;
import com.award.log.collector.LogCollectorManager;
import com.award.log.analysis.LogLineParseSupport;
import com.award.log.analysis.GeneralLogSeverityResolver;
import com.award.log.model.LogSeverityLevel;
import com.award.log.model.LogDocument;
import com.award.log.service.ElasticsearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.alibaba.fastjson.JSON;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.ZoneId;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 实时日志流 WebSocket（路径见 {@code WebSocketConfig}：{@code /ws/logs}）。
 * <p>
 * <b>旧问题（多人并发易 OOM）：</b>每个连接各自死循环调用 {@code takeFanOut()}，
 * N 个客户端抢同一条队列，并且每人都会重复分析 + 写 ES；同时多线程对同一
 * {@link WebSocketSession} 调 {@code sendMessage}（非线程安全）。
 * <p>
 * <b>现行模型：</b>
 * <ol>
 *   <li>全局只有一个调度任务从 fan-out 取日志</li>
 *   <li>分析 / 索引只做一次</li>
 *   <li>再广播给所有已连接会话</li>
 *   <li>会话一律包成 {@link ConcurrentWebSocketSessionDecorator}，限制发送缓冲</li>
 *   <li>连接数硬上限 {@link #MAX_SESSIONS}，超限直接拒连</li>
 * </ol>
 */
@Slf4j
@Component
public class LogStreamWebSocketHandler extends TextWebSocketHandler {

    /** 同时允许的最大日志流连接数；演示/多人时防止会话与发送缓冲无限涨 */
    static final int MAX_SESSIONS = 64;
    /** 单次 send 最长阻塞时间（毫秒），超时由 decorator 处理，避免慢客户端拖死推送线程 */
    private static final long SEND_TIME_LIMIT_MS = 10_000L;
    /** 单会话发送缓冲上限（字节），超限丢消息而不是撑爆堆 */
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;
    private static final long PUSH_INTERVAL_MS = 1_000L;
    private static final long MEMORY_CHECK_INTERVAL = 30_000L;
    private static final double MEMORY_THRESHOLD_HIGH = 0.8;
    private static final double MEMORY_THRESHOLD_MEDIUM = 0.6;
    private static final int MAX_BATCH_SIZE = 500;
    private static final int MIN_BATCH_SIZE = 20;

    /** sessionId → 已包装的可并发发送会话 */
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    /** 当前从 fan-out 一次最多取多少行；内存紧张时会调小 */
    private volatile int batchSize = 100;
    private volatile boolean realTimeAnalysisStarted;

    /** 唯一调度线程：推送 + 内存巡检，禁止按内存重建线程池（旧实现会杀光流任务） */
    private ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pushTask;

    @Autowired
    private LogCollectorManager logCollectorManager;

    @Autowired
    private RealTimeLogAnalyzer realTimeLogAnalyzer;

    @Autowired(required = false)
    private ElasticsearchService elasticsearchService;

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-stream-push");
            t.setDaemon(true);
            return t;
        });
        // 只调 batchSize，不重建线程池
        scheduler.scheduleAtFixedRate(() -> {
            try {
                adjustBatchSize(getMemoryUsage());
            } catch (Exception e) {
                log.error("内存检查任务执行失败: {}", e.getMessage(), e);
            }
        }, MEMORY_CHECK_INTERVAL, MEMORY_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
        try {
            if (realTimeLogAnalyzer != null) {
                realTimeLogAnalyzer.registerWebSocketHandler(this);
                log.info("已注册到实时日志分析器的消息广播器");
            }
        } catch (Exception e) {
            log.warn("注册到实时日志分析器失败: {}", e.getMessage());
        }
        startPushIfNeeded();
    }

    @PreDestroy
    public void destroy() {
        stopPush();
        if (realTimeLogAnalyzer != null) {
            realTimeLogAnalyzer.unregisterWebSocketHandler(this);
        }
        activeSessions.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        return (double) usedMemory / maxMemory;
    }

    /** 堆压力高时减小单次取数，降低瞬时 JSON / 分析峰值。 */
    private void adjustBatchSize(double memoryUsage) {
        int newBatchSize;
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            newBatchSize = MIN_BATCH_SIZE;
        } else if (memoryUsage > MEMORY_THRESHOLD_MEDIUM) {
            newBatchSize = 100;
        } else {
            newBatchSize = MAX_BATCH_SIZE;
        }
        if (newBatchSize != batchSize) {
            batchSize = newBatchSize;
            log.info("内存使用率 {} , 批处理大小调整为: {}", String.format("%.2f", memoryUsage), newBatchSize);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (activeSessions.size() >= MAX_SESSIONS) {
            log.warn("日志流 WebSocket 连接数已达上限 {}，拒绝 session={}", MAX_SESSIONS, session.getId());
            session.close(CloseStatus.POLICY_VIOLATION.withReason("too many log stream connections"));
            return;
        }
        WebSocketSession safe = wrap(session);
        activeSessions.put(safe.getId(), safe);
        log.info("WebSocket连接建立: {}", safe.getId());
        sendQuietly(safe, "{\"type\": \"connection\", \"message\": \"连接成功\", \"sessionId\": \""
                + escapeJson(safe.getId()) + "\"}");
        startPushIfNeeded();
        startRealtimeOnce();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocketSession safe = resolve(session);
        if (safe == null) {
            return;
        }
        String payload = message.getPayload();
        log.debug("收到WebSocket消息: {} from {}", payload, safe.getId());
        sendQuietly(safe, "{\"type\": \"echo\", \"message\": \"收到消息\", \"payload\": \""
                + escapeJson(payload) + "\"}");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session.getId());
        log.info("WebSocket连接关闭: {}, 状态: {}", session.getId(), status);
        // Keep the server-side analysis consumer alive when all viewers disconnect.
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        WebSocketSession safe = resolve(session);
        log.error("WebSocket传输错误: {} from {}", exception.getMessage(), session.getId());
        if (safe != null && safe.isOpen()) {
            sendQuietly(safe, "{\"type\": \"error\", \"message\": \"传输错误: "
                    + escapeJson(String.valueOf(exception.getMessage())) + "\"}");
        }
    }

    /** 首个活跃连接时确保唯一的全局采集/分析消费者已运行。 */
    private void startPushIfNeeded() {
        ScheduledExecutorService exec = scheduler;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        ScheduledFuture<?> current = pushTask;
        if (current != null && !current.isCancelled() && !current.isDone()) {
            return;
        }
        pushTask = exec.scheduleAtFixedRate(() -> {
            try {
                pushLogsOnce();
            } catch (Exception e) {
                log.error("日志流推送失败: {}", e.getMessage(), e);
            }
        }, 0, PUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPush() {
        ScheduledFuture<?> current = pushTask;
        if (current != null) {
            current.cancel(false);
            pushTask = null;
        }
    }

    /** 进程内只启动一次实时分析环。 */
    private void startRealtimeOnce() {
        if (realTimeAnalysisStarted || realTimeLogAnalyzer == null) {
            return;
        }
        synchronized (this) {
            if (realTimeAnalysisStarted) {
                return;
            }
            realTimeLogAnalyzer.startRealTimeAnalysis();
            realTimeAnalysisStarted = true;
            log.info("实时日志分析任务已启动");
        }
    }

    /**
     * 单次推送：取一批 → 广播 → 分析/索引一次。
     * 注意：不要在这里为每个 session 再 takeFanOut。
     */
    void pushLogsOnce() {
        if (logCollectorManager == null) {
            return;
        }
        List<String> logs = logCollectorManager.takeFanOut(batchSize);
        if (logs.isEmpty()) {
            return;
        }
        processLogBatch(logs);
    }

    /** 先广播原始行，再统一喂给实时分析器 / ES（每批只做一次）。 */
    private void processLogBatch(List<String> logBatch) {
        try {
            String logData = "{\"type\": \"logs\", \"data\": " + JSON.toJSONString(logBatch) + "}";
            broadcastLogData(logData);

            List<Map<String, Object>> parsedLogs = parseLogLines(logBatch);
            for (Map<String, Object> logItem : parsedLogs) {
                if (realTimeLogAnalyzer != null) {
                    try {
                        realTimeLogAnalyzer.addLog(logItem);
                    } catch (Exception e) {
                        log.warn("实时分析器处理日志失败: {}", e.getMessage());
                    }
                }
                if (elasticsearchService != null) {
                    try {
                        LogDocument logDocument = convertToLogDocument(logItem);
                        elasticsearchService.indexLog(logDocument);
                    } catch (Exception e) {
                        log.warn("索引日志到Elasticsearch失败: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("处理日志批处理失败: {}", e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> parseLogLines(List<String> logs) {
        List<Map<String, Object>> parsedLogs = new ArrayList<>(logs.size());
        for (String line : logs) {
            Map<String, Object> item = new HashMap<>();
            item.put("content", line);
            item.put("level", inferLevel(line));
            item.put("timestamp", eventTimestamp(line));
            parsedLogs.add(item);
        }
        return parsedLogs;
    }

    private static long eventTimestamp(String line) {
        String normalized=LogLineParseSupport.extractNormalizedTimeString(line);
        if(normalized==null || normalized.isBlank()) return System.currentTimeMillis();
        try { return LogLineParseSupport.toEpochMillis(normalized); }
        catch(RuntimeException invalid) { return System.currentTimeMillis(); }
    }

    private static String inferLevel(String line) {
        LogSeverityLevel severity=GeneralLogSeverityResolver.resolve(line);
        return switch(severity) {
            case FATAL_LEVEL -> "FATAL";
            case ERROR_LEVEL -> "ERROR";
            case WARNING_LEVEL -> "WARN";
            case INFO_LEVEL -> "INFO";
            case DEBUG_LEVEL -> "DEBUG";
            default -> "INFO";
        };
    }

    /**
     * 向所有活跃会话广播（含实时分析器回调的结果）。
     * 必须对包装后的 session 发送，保证与周期推送线程安全。
     */
    public void broadcastLogData(String logData) {
        TextMessage textMessage = new TextMessage(logData);
        for (WebSocketSession session : activeSessions.values()) {
            sendQuietly(session, textMessage);
        }
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /** 优先返回建连时存好的 decorator；找不到再临时包装。 */
    private WebSocketSession resolve(WebSocketSession session) {
        if (session == null) {
            return null;
        }
        WebSocketSession stored = activeSessions.get(session.getId());
        return stored != null ? stored : wrap(session);
    }

    /**
     * Spring 原生 session 不支持并发 send；包一层后允许多线程安全发送，
     * 并限制时间/缓冲，避免慢客户端堆积。
     */
    private static WebSocketSession wrap(WebSocketSession session) {
        if (session instanceof ConcurrentWebSocketSessionDecorator) {
            return session;
        }
        return new ConcurrentWebSocketSessionDecorator(session, (int) SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
    }

    private static void sendQuietly(WebSocketSession session, String json) {
        sendQuietly(session, new TextMessage(json));
    }

    private static void sendQuietly(WebSocketSession session, TextMessage message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(message);
        } catch (Exception e) {
            log.warn("发送 WebSocket 消息失败 session={}: {}", session.getId(), e.getMessage());
        }
    }

    private static String escapeJson(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private LogDocument convertToLogDocument(Map<String, Object> log) {
        LogDocument document = new LogDocument();
        document.setId(java.util.UUID.randomUUID().toString());
        Object eventTimestamp = log.get("timestamp");
        if (eventTimestamp instanceof Number epochMillis) {
            document.setTimestamp(Instant.ofEpochMilli(epochMillis.longValue())
                    .atZone(ZoneId.systemDefault()).toLocalDateTime());
        } else {
            document.setTimestamp(java.time.LocalDateTime.now());
        }

        String level = (String) log.get("level");
        document.setSeverity(level != null ? level : "INFO");

        String content = (String) log.get("content");
        if (content == null && log.get("message") != null) {
            content = (String) log.get("message");
        }
        document.setContent(content != null ? content : "无内容");
        document.setProtocol("WEBSOCKET_STREAM");

        document.setAnomaly("ERROR".equalsIgnoreCase(level)
                || "WARN".equalsIgnoreCase(level)
                || "FATAL".equalsIgnoreCase(level));
        return document;
    }
}
