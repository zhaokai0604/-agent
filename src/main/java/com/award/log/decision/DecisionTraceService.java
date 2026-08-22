package com.award.log.decision;

import com.award.log.mapper.DecisionLogMapper;
import com.award.log.model.DecisionLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 将一次协同决策的输入、输出与耗时写入 decision_log。
 * 序列化或入库失败时吞掉异常并返回空串，避免阻断主决策链路。
 */
@Service
public class DecisionTraceService {

    private final DecisionLogMapper decisionLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DecisionTraceService(DecisionLogMapper decisionLogMapper) {
        this.decisionLogMapper = decisionLogMapper;
    }

    /** 落库一条决策轨迹，失败返回空字符串而非抛出。 */
    public String record(DecisionInput input, DecisionResult result, long latencyMs) {
        try {
            DecisionLog log = new DecisionLog();
            String decisionId = UUID.randomUUID().toString().replace("-", "");
            log.setDecisionId(decisionId);
            log.setEngineType(result.getEngineType().name());
            log.setShouldAlert(result.isShouldAlert() ? 1 : 0);
            log.setConfidence(result.getConfidence());
            log.setLatencyMs(latencyMs);
            log.setInputJson(objectMapper.writeValueAsString(input));
            log.setOutputJson(objectMapper.writeValueAsString(result));
            log.setTraceJson(objectMapper.writeValueAsString(Map.of("reason", result.getReason())));
            decisionLogMapper.insert(log);
            return decisionId;
        } catch (Exception e) {
            return "";
        }
    }
}
