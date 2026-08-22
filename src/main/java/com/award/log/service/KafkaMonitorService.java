package com.award.log.service;

import java.util.Map;

/**
 * Kafka 集群快照：broker / topic / consumer group。
 */
public interface KafkaMonitorService {
    Map<String, Object> snapshot();
}
