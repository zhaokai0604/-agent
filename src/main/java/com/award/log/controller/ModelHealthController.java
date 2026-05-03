package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.decision.RandomForestDecisionEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模型健康检查接口
 */
@Tag(name = "Model")
@RestController
@RequestMapping("/api/v1/model")
public class ModelHealthController {

    private final RandomForestDecisionEngine randomForestDecisionEngine;

    public ModelHealthController(RandomForestDecisionEngine randomForestDecisionEngine) {
        this.randomForestDecisionEngine = randomForestDecisionEngine;
    }

    @Operation(summary = "模型健康状态")
    @GetMapping("/health")
    public Result<Map<String, Object>> rfHealth() {
        return Result.success(randomForestDecisionEngine.healthSnapshot(), "获取随机森林模型状态成功");
    }

    @Operation(summary = "手动重载模型")
    @PostMapping("/reload")
    public Result<Boolean> reload() {
        boolean ok = randomForestDecisionEngine.manualReload();
        return ok ? Result.success(true, "模型重载成功") : Result.error("模型重载失败");
    }
}
