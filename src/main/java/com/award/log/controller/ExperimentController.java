package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.ExperimentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Experiment")
@RestController
@RequestMapping("/api/v1/experiment")
public class ExperimentController {

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @Operation(summary = "启动实验")
    @PostMapping("/start")
    public Result<Boolean> start(@RequestBody Map<String, String> body) {
        return Result.success(experimentService.start(body.getOrDefault("name", "ab-default")));
    }

    @Operation(summary = "停止实验")
    @PostMapping("/stop")
    public Result<Boolean> stop() {
        return Result.success(experimentService.stop());
    }

    @Operation(summary = "实验报告")
    @GetMapping("/report")
    public Result<Map<String, Object>> report() {
        return Result.success(experimentService.report());
    }
}
