package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.AgentPathPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理员通过控制台维护 MCP 路径白名单与服务重启白名单（热加载，持久化到 agent-path-policy-overrides.json）。
 */
@RestController
@RequestMapping("/api/agent/path-policy")
@RequiredArgsConstructor
public class AgentPathPolicyController {

    private final AgentPathPolicyService agentPathPolicyService;

    @GetMapping
    public Result<Map<String, Object>> getPolicy() {
        return Result.success(agentPathPolicyService.getEffectivePolicyView());
    }

    @PutMapping
    public Result<Map<String, Object>> savePolicy(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> data = agentPathPolicyService.saveEditablePolicy(body);
            data.put("message", "白名单已保存并生效");
            return Result.success(data);
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
    }
}
