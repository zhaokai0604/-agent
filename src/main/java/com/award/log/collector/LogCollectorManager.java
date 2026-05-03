package com.award.log.collector;

import com.award.log.collector.impl.KafkaLogProducer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志采集器管理器
 * 用于统一管理多个日志采集器实例
 */
@Slf4j
public class LogCollectorManager {

    private final Map<String, LogCollector> collectors;
    private KafkaLogProducer kafkaLogProducer;

    public LogCollectorManager() {
        this.collectors = new ConcurrentHashMap<>();
    }

    public void setKafkaLogProducer(KafkaLogProducer kafkaLogProducer) {
        this.kafkaLogProducer = kafkaLogProducer;
    }

    /**
     * 添加采集器
     * @param collector 日志采集器实例
     */
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

    /**
     * 移除采集器
     * @param name 采集器名称
     */
    public void removeCollector(String name) {
        LogCollector collector = collectors.remove(name);
        if (collector != null) {
            collector.stop();
            log.info("[采集器管理器] 移除采集器: {}", name);
        }
    }

    /**
     * 启动所有采集器
     */
    public void startAll() {
        log.info("[采集器管理器] 启动所有采集器");
        collectors.values().forEach(LogCollector::start);
    }

    /**
     * 停止所有采集器
     */
    public void stopAll() {
        log.info("[采集器管理器] 停止所有采集器");
        collectors.values().forEach(LogCollector::stop);
    }

    /**
     * 采集所有日志
     * @return 所有采集器采集的日志列表
     */
    public List<String> collectAll() {
        List<String> allLogs = new ArrayList<>();
        collectors.values().forEach(collector -> {
            if (collector.isRunning()) {
                List<String> logs = collector.collect();
                allLogs.addAll(logs);
            }
        });
        return allLogs;
    }

    /**
     * 获取采集器状态
     * @return 采集器状态映射
     */
    public Map<String, Boolean> getCollectorStatus() {
        Map<String, Boolean> statusMap = new ConcurrentHashMap<>();
        collectors.forEach((name, collector) -> {
            statusMap.put(name, collector.isRunning());
        });
        return statusMap;
    }

    /**
     * 获取采集器数量
     * @return 采集器数量
     */
    public int getCollectorCount() {
        return collectors.size();
    }

    /**
     * 获取指定采集器
     * @param name 采集器名称
     * @return 采集器实例
     */
    public LogCollector getCollector(String name) {
        return collectors.get(name);
    }
}
