package com.award.log.controller;

import com.award.log.agent.OpsAnalysisSkillCatalog;
import com.award.log.common.Result;
import com.award.log.service.AiAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可主动调用的 AI 分析 Skill：列出提示词技能并同步/异步执行。
 */
@Slf4j
@Tag(name = "Ops Analysis Skills")
@RestController
@RequestMapping("/api/ops/analysis-skills")
public class OpsAnalysisSkillController {

    private final AiAnalysisService aiAnalysisService;

    public OpsAnalysisSkillController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @Operation(summary = "列出可主动调用的 AI 分析 Skill")
    @GetMapping
    public Result<List<Map<String, Object>>> listSkills() {
        return Result.success(OpsAnalysisSkillCatalog.listViews());
    }

    @Operation(summary = "主动调用指定 AI 分析 Skill")
    @PostMapping("/{skillId}/invoke")
    public Result<Map<String, Object>> invokeSkill(@PathVariable String skillId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        return OpsAnalysisSkillCatalog.find(skillId)
                .map(def -> {
                    Map<String, Object> input = body == null ? Map.of() : body;
                    String missing = firstMissingRequired(def, input);
                    if (missing != null) {
                        return Result.<Map<String, Object>>error("缺少必填参数：" + missing);
                    }
                    log.info("Invoke analysis skill: {}", def.id());
                    String answer = aiAnalysisService.invokeSkill(def.id(), input);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("skillId", def.id());
                    payload.put("skillName", def.name());
                    payload.put("answer", answer);
                    payload.put("outputSections", def.outputSections());
                    return Result.success(payload);
                })
                .orElseGet(() -> Result.error(404, "未找到 Skill：" + skillId));
    }

    private static String firstMissingRequired(OpsAnalysisSkillCatalog.SkillDefinition def, Map<String, Object> input) {
        for (OpsAnalysisSkillCatalog.SkillParam param : def.params()) {
            if (!param.required()) {
                continue;
            }
            Object v = input.get(param.key());
            if (v == null || String.valueOf(v).isBlank()) {
                return param.label();
            }
        }
        return null;
    }
}
