package com.award.log.actuator;

import com.award.log.service.EngineHealthService;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Actuator 自定义端点 {@code /actuator/engine-health}：暴露引擎健康快照。
 * 只读聚合，不触发引擎重启或模型重载。
 */
@Component
@Endpoint(id = "engine-health")
public class EngineHealthEndpoint {

    private final EngineHealthService engineHealthService;

    public EngineHealthEndpoint(EngineHealthService engineHealthService) {
        this.engineHealthService = engineHealthService;
    }

    @ReadOperation
    /** 返回各引擎/依赖的健康快照。 */
    public Map<String, Object> health() {
        return engineHealthService.snapshot();
    }
}
