package com.award.log.controller;

import com.award.log.agent.AutonomousOpsOrchestrator;
import com.award.log.common.Result;
import com.award.log.security.McpToolSurface;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "统一感知")
@RestController
@RequestMapping("/api/ops/perception")
@RequiredArgsConstructor
public class PerceptionController {

    private final AutonomousOpsOrchestrator autonomousOpsOrchestrator;

    @Operation(summary = "采集主机感知快照与规则诊断 findings（只读，不执行写操作）")
    @GetMapping("/snapshot")
    public Result<Map<String, Object>> snapshot(
            @RequestParam(defaultValue = "true") boolean useCache
    ) {
        return Result.success(autonomousOpsOrchestrator.buildPerceptionView(useCache, McpToolSurface.READ_ONLY));
    }
}
