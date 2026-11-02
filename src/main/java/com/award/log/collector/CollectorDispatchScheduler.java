package com.award.log.collector;

import com.award.log.collector.impl.KafkaLogProducer;
import com.award.log.collector.model.RawLogEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集层调度器：定时 {@link LogCollectorManager#collectAll()} 排水，
 * 可选再推 Kafka 原始 Topic。这是 fan-out 的<strong>唯一主消费者入口</strong>。
 */
@Slf4j
@Component
public class CollectorDispatchScheduler {

    private final LogCollectorManager logCollectorManager;
    private final KafkaLogProducer kafkaLogProducer;

    public CollectorDispatchScheduler(LogCollectorManager logCollectorManager,
            @Autowired(required = false) KafkaLogProducer kafkaLogProducer) {
        this.logCollectorManager = logCollectorManager;
        this.kafkaLogProducer = kafkaLogProducer;
    }
    private final AtomicLong offsetGenerator = new AtomicLong(0);
    private final AtomicLong totalCollected = new AtomicLong(0);
    private final AtomicLong totalDispatched = new AtomicLong(0);
    private final AtomicLong lastBatchSize = new AtomicLong(0);
    private final AtomicLong lastDispatchTime = new AtomicLong(0);
    private final AtomicLong dispatchFailures = new AtomicLong(0);
    private PendingBatch pending;
    private java.util.concurrent.CompletableFuture<Void> delivery;
    private record PendingBatch(List<RawLogEvent> events, Runnable acknowledge) {}

    @Value("${log.pipeline.collector.dispatch-enabled:true}")
    private boolean dispatchEnabled;

    @Value("${log.pipeline.collector.source-id:file-watcher}")
    private String sourceId;

    @Scheduled(fixedDelayString = "${log.pipeline.collector.dispatch-interval-ms:1000}")
    public synchronized void dispatch() {
        if (!dispatchEnabled) {
            return;
        }
        if (pending != null) {
            finishOrRetryDelivery();
            return;
        }
        // 始终由调度器单点 drain；Kafka 未装配时仍 fan-out，供 WebSocket 消费
        CollectedLogBatch batch = logCollectorManager.collectBatch();
        List<String> collected = batch.lines();
        totalCollected.addAndGet(collected.size());
        if (collected.isEmpty()) {
            lastBatchSize.set(0);
            return;
        }

        if (kafkaLogProducer == null) {
            lastBatchSize.set(collected.size());
            lastDispatchTime.set(System.currentTimeMillis());
            return;
        }

        List<RawLogEvent> events = new ArrayList<>(collected.size());
        long now = System.currentTimeMillis();
        for (String line : collected) {
            RawLogEvent event = new RawLogEvent();
            event.setEventId(UUID.randomUUID().toString());
            event.setSourceId(sourceId);
            event.setSourceType("FILE");
            event.setHost(localHost());
            event.setOffset(offsetGenerator.incrementAndGet());
            event.setIngestTime(now);
            event.setEventTime(now);
            event.setLevel(detectLevel(line));
            event.setContent(line);
            events.add(event);
        }
        pending = new PendingBatch(List.copyOf(events), batch.acknowledge());
        finishOrRetryDelivery();
    }

    private void finishOrRetryDelivery() {
        if (delivery == null) delivery = kafkaLogProducer.sendRawEventsAcknowledged(pending.events());
        if (!delivery.isDone()) return;
        try {
            delivery.join();
            pending.acknowledge().run();
            totalDispatched.addAndGet(pending.events().size());
            lastBatchSize.set(pending.events().size());
            lastDispatchTime.set(System.currentTimeMillis());
            pending = null;
        } catch (RuntimeException failure) {
            dispatchFailures.incrementAndGet();
            log.warn("[采集调度] 持久交付或检查点失败，保留批次等待重试");
        } finally {
            delivery = null;
        }
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("dispatchEnabled", dispatchEnabled);
        snapshot.put("sourceId", sourceId);
        snapshot.put("totalCollected", totalCollected.get());
        snapshot.put("totalDispatched", totalDispatched.get());
        snapshot.put("lastBatchSize", lastBatchSize.get());
        snapshot.put("lastDispatchTime", lastDispatchTime.get());
        snapshot.put("dispatchFailures", dispatchFailures.get());
        snapshot.put("pendingDeliveryLines", pending == null ? 0 : pending.events().size());
        snapshot.put("checkpointReceiver", kafkaLogProducer == null ? "NONE_REPLAY_ON_RESTART" : "KAFKA_BROKER_RECEIPT");
        snapshot.put("collectorStatus", logCollectorManager.getCollectorStatus());
        snapshot.put("collectorCount", logCollectorManager.getCollectorCount());
        snapshot.put("collectorDiagnostics", logCollectorManager.getCollectorDiagnostics());
        return snapshot;
    }

    private String localHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private String detectLevel(String line) {
        String lower = line == null ? "" : line.toLowerCase();
        if (lower.contains("fatal")) {
            return "FATAL";
        }
        if (lower.contains("error") || lower.contains("exception")) {
            return "ERROR";
        }
        if (lower.contains("warn")) {
            return "WARN";
        }
        if (lower.contains("debug")) {
            return "DEBUG";
        }
        return "INFO";
    }
}
