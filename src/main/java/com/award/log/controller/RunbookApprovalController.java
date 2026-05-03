package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.AuthInterceptor;
import com.award.log.service.impl.RunbookApprovalService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runbook")
public class RunbookApprovalController {

    private final RunbookApprovalService runbookApprovalService;

    public RunbookApprovalController(RunbookApprovalService runbookApprovalService) {
        this.runbookApprovalService = runbookApprovalService;
    }

    @PostMapping("/submit")
    public Result<Map<String, Object>> submit(@RequestBody Map<String, String> payload, HttpSession session) {
        Object requester = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        Map<String, Object> item = runbookApprovalService.submit(
                payload.getOrDefault("title", "Runbook"),
                payload.getOrDefault("action", ""),
                payload.getOrDefault("command", ""),
                requester
        );
        return Result.success(item);
    }

    @PostMapping("/{id}/approve")
    public Result<Map<String, Object>> approve(@PathVariable long id, HttpSession session) {
        Object role = session.getAttribute(AuthInterceptor.SESSION_USER_ROLE);
        if (!(role instanceof Number) || ((Number) role).intValue() != 1) {
            return Result.error(403, "仅管理员可审批");
        }
        Object approver = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        return Result.success(runbookApprovalService.approve(id, approver));
    }

    @PostMapping("/{id}/reject")
    public Result<Map<String, Object>> reject(@PathVariable long id, @RequestBody(required = false) Map<String, String> body, HttpSession session) {
        Object role = session.getAttribute(AuthInterceptor.SESSION_USER_ROLE);
        if (!(role instanceof Number) || ((Number) role).intValue() != 1) {
            return Result.error(403, "仅管理员可审批");
        }
        Object approver = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        String reason = body == null ? "" : body.getOrDefault("reason", "");
        return Result.success(runbookApprovalService.reject(id, approver, reason));
    }

    @PostMapping("/{id}/execute")
    public Result<Map<String, Object>> execute(@PathVariable long id, HttpSession session) {
        Object role = session.getAttribute(AuthInterceptor.SESSION_USER_ROLE);
        if (!(role instanceof Number) || ((Number) role).intValue() != 1) {
            return Result.error(403, "仅管理员可执行");
        }
        Object operator = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        return Result.success(runbookApprovalService.execute(id, operator));
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.success(runbookApprovalService.list());
    }
}
