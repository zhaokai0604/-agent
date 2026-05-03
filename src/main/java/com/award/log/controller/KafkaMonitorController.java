package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.KafkaMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/kafka")
public class KafkaMonitorController {

    private final KafkaMonitorService kafkaMonitorService;

    public KafkaMonitorController(@Autowired(required = false) KafkaMonitorService kafkaMonitorService) {
        this.kafkaMonitorService = kafkaMonitorService;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        if (kafkaMonitorService == null) {
            Map<String, Object> body = new HashMap<>();
            body.put("online", false);
            body.put("message", "Kafka 未启用（award.middleware.kafka=false）");
            return Result.success(body, "Kafka 未启用");
        }
        return Result.success(kafkaMonitorService.snapshot(), "获取Kafka真实监控状态成功");
    }
}
