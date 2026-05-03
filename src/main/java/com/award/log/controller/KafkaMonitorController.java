package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.KafkaMonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/kafka")
public class KafkaMonitorController {

    private final KafkaMonitorService kafkaMonitorService;

    public KafkaMonitorController(KafkaMonitorService kafkaMonitorService) {
        this.kafkaMonitorService = kafkaMonitorService;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(kafkaMonitorService.snapshot(), "获取Kafka真实监控状态成功");
    }
}
