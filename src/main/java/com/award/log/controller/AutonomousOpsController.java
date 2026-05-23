package com.award.log.controller;

import com.award.log.agent.OpsIntentRouter;
import com.award.log.agent.OpsRuntimeService;
import com.award.log.common.Result;
import com.award.log.security.McpToolSurface;
import com.award.log.security.RiskLevel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全栈自主运维 API：不依赖 LLM 也可触发「感知→诊断→处置→验证」。
 */
@Tag(name = "自主运维")
@RestController
@RequestMapping("/api/ops/autonomous")
@RequiredArgsConstructor
public class AutonomousOpsController {

    private final OpsRuntimeService opsRuntimeService;
    private final OpsIntentRouter opsIntentRouter;

    @Operation(summary = "执行全工具融合自主运维闭环")
    @PostMapping("/run")
    public Result<Map<String, Object>> run(
            @RequestParam(defaultValue = "false") boolean forceRemediate,
            @RequestParam(defaultValue = "false") boolean readOnly
    ) {
        McpToolSurface surface = readOnly ? McpToolSurface.READ_ONLY : McpToolSurface.FULL;
        OpsRuntimeService.RunResult result =
                opsRuntimeService.runScheduledAutonomous(surface, forceRemediate);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", result.traceId());
        body.put("markdown", result.markdown());
        body.put("report", result.report());
        return Result.success(body);
    }

    @Operation(summary = "是否支持该自然语言意图走自主运维编排")
    @GetMapping("/supports")
    public Result<Map<String, Object>> supports(@RequestParam String message) {
        boolean ok = opsIntentRouter.resolve(message) == OpsIntentRouter.Playbook.FULL_AUTONOMOUS;
        return Result.success(Map.of("supports", ok));
    }
}
