package com.award.log.collector;

import com.award.log.collector.impl.KafkaLogProducer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志采集器管理器：统一注册/启停多个 {@link LogCollector}。
 * <p>
 * <b>Fan-out 队列设计（防 OOM / 防抢消费）：</b>
 * <ul>
 *   <li>主调度器调用 {@link #collectAll()} 从各采集器排水，同时把副本写入 {@code fanOutQueue}</li>
 *   <li>WebSocket 等次级消费者只读 fan-out，不再直接 {@code collector.collect()}，避免把主缓冲抢空</li>
 *   <li>队列有硬容量；满了丢最旧，保证堆内存有上限</li>
 *   <li>主消费者用 {@link #takeFanOut(int)} 有界取出；旁路观察用 {@link #snapshotFanOut(int)} 只读快照</li>
 * </ul>
 */
@Slf4j
public class LogCollectorManager {

    /** fan-out 队列最大行数；超过则丢弃最旧行，防止堆积撑爆堆 */
    static final int FAN_OUT_CAPACITY = 10_000;
    /** 默认单次取出上限，避免一次 JSON 序列化整队 1 万行 */
    static final int DEFAULT_TAKE_BATCH = 500;

    private final Map<String, LogCollector> collectors;
    private KafkaLogProducer kafkaLogProducer;

    /**
     * 次级消费者（WebSocket 等）专用队列。
     * 用有界 {@link ArrayBlockingQueue}，不要用 ConcurrentLinkedQueue.size() 做容量判断（O(n) 且不准）。
     */
    private final ArrayBlockingQueue<String> fanOutQueue = new ArrayBlockingQueue<>(FAN_OUT_CAPACITY);

    public LogCollectorManager() {
        this.collectors = new ConcurrentHashMap<>();
    }

    public void setKafkaLogProducer(KafkaLogProducer kafkaLogProducer) {
        this.kafkaLogProducer = kafkaLogProducer;
    }

    /** 按名称注册采集器；重名则忽略。 */
    public void addCollector(LogCollector collector) {
        if (collector == null) {
            log.warn("[采集器管理器] 无效的采集器实例");
            return;
        }
        String name = collector.getName();
        if (collectors.containsKey(name)) {
            log.warn("[采集器管理器] 采集器名称已存在: {}", name);
            return;
        }
        collectors.put(name, collector);
        log.info("[采集器管理器] 添加采集器: {}", name);
    }

    /** 移除并停止指定采集器。 */
    public void removeCollector(String name) {
        LogCollector collector = collectors.remove(name);
        if (collector != null) {
            collector.stop();
            log.info("[采集器管理器] 移除采集器: {}", name);
        }
    }

    /** 启动全部已注册采集器。 */
    public void startAll() {
        log.info("[采集器管理器] 启动所有采集器");
        collectors.values().forEach(LogCollector::start);
    }

    /** 停止全部已注册采集器。 */
    public void stopAll() {
        log.info("[采集器管理器] 停止所有采集器");
        collectors.values().forEach(LogCollector::stop);
    }

    /**
     * 主消费路径：从各采集器 drain 一次，并把副本写入 fan-out。
     * <p>仅应由调度器调用；多个线程同时 collect 会互相抢空缓冲。
     */
    public List<String> collectAll() {
        List<String> allLogs = new ArrayList<>();
        collectors.values().forEach(collector -> {
            if (collector.isRunning()) {
                List<String> logs = collector.collect();
                allLogs.addAll(logs);
            }
        });
        publishFanOut(allLogs);
        return allLogs;
    }

    /** 主消费者：按默认批次取出并移出队列。 */
    public List<String> takeFanOut() {
        return takeFanOut(DEFAULT_TAKE_BATCH);
    }

    /**
     * 主消费者：最多取出 {@code maxBatch} 行并移出队列。
     * 多连接场景必须只有一个主消费者调用本方法，否则会互相抢日志。
     */
    public List<String> takeFanOut(int maxBatch) {
        int cap = Math.max(1, Math.min(maxBatch, FAN_OUT_CAPACITY));
        List<String> out = new ArrayList<>(Math.min(cap, fanOutQueue.size() + 1));
        fanOutQueue.drainTo(out, cap);
        return out;
    }

    /**
     * 次级观察者只读快照：不移出队列，不与主消费者抢数据。
     * 例如旧版 {@code LogWebSocketHandler} 巡检推送可走此接口。
     */
    public List<String> snapshotFanOut(int max) {
        int limit = Math.max(0, max);
        if (limit == 0) {
            return List.of();
        }
        Object[] snapshot = fanOutQueue.toArray();
        int n = Math.min(limit, snapshot.length);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (snapshot[i] instanceof String line) {
                out.add(line);
            }
        }
        return out;
    }

    /** 测试用：当前 fan-out 队列长度。 */
    int fanOutSize() {
        return fanOutQueue.size();
    }

    /**
     * 写入 fan-out：队列满时先丢最旧再 offer，保证容量硬上限。
     * （offer 失败说明满了；poll 腾出空位后重试。）
     */
    void publishFanOut(List<String> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        for (String line : logs) {
            if (line == null) {
                continue;
            }
            while (!fanOutQueue.offer(line)) {
                fanOutQueue.poll();
            }
        }
    }

    /** @return 采集器名 → 是否在运行 */
    public Map<String, Boolean> getCollectorStatus() {
        Map<String, Boolean> statusMap = new ConcurrentHashMap<>();
        collectors.forEach((name, collector) -> {
            statusMap.put(name, collector.isRunning());
        });
        return statusMap;
    }

    public int getCollectorCount() {
        return collectors.size();
    }

    public LogCollector getCollector(String name) {
        return collectors.get(name);
    }
}
