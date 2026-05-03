package com.award.log.controller;

import com.award.log.collector.CollectorDispatchScheduler;
import com.award.log.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/collector")
public class CollectorStatusController {

    private final CollectorDispatchScheduler collectorDispatchScheduler;

    public CollectorStatusController(CollectorDispatchScheduler collectorDispatchScheduler) {
        this.collectorDispatchScheduler = collectorDispatchScheduler;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(collectorDispatchScheduler.snapshot(), "获取采集器状态成功");
    }
}
