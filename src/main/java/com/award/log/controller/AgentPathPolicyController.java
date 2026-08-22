package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.AgentPathPolicyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 可执行路径策略管理：限制工具落盘与命令白名单。
 * HTTP 前缀：{@code /api/agent/path-policy}。
 * 查看与保存均需管理员，避免普通用户改写执行边界。
 */
@RestController
@RequestMapping("/api/agent/path-policy")
@RequiredArgsConstructor
public class AgentPathPolicyController {

    private final AgentPathPolicyService agentPathPolicyService;
    private final RequestUserResolver requestUserResolver;

    @GetMapping
    public Result<Map<String, Object>> getPolicy(HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "Only administrators can view path policy");
        }
        return Result.success(agentPathPolicyService.getEffectivePolicyView());
    }

    @PutMapping
    public Result<Map<String, Object>> savePolicy(HttpServletRequest request,
                                                  @RequestBody Map<String, Object> body) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "Only administrators can update path policy");
        }
        try {
            Map<String, Object> data = agentPathPolicyService.saveEditablePolicy(body);
            data.put("message", "Path policy saved");
            return Result.success(data);
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
    }
}
