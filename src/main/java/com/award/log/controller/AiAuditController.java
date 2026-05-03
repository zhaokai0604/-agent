package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.AuthInterceptor;
import com.award.log.service.impl.AiAuditLogService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/audit/ai")
public class AiAuditController {

    private final AiAuditLogService aiAuditLogService;
    private final boolean aiAuditRelaxedRead;

    public AiAuditController(
            AiAuditLogService aiAuditLogService,
            @Value("${app.security.ai-audit-relaxed-read:false}") boolean aiAuditRelaxedRead) {
        this.aiAuditLogService = aiAuditLogService;
        this.aiAuditRelaxedRead = aiAuditRelaxedRead;
    }

    @GetMapping("/recent")
    public Result<List<Map<String, Object>>> recent(@RequestParam(defaultValue = "100") int limit, HttpSession session) {
        if (!this.aiAuditRelaxedRead) {
            Object role = session.getAttribute(AuthInterceptor.SESSION_USER_ROLE);
            if (!(role instanceof Number) || ((Number) role).intValue() != 1) {
                return Result.error(403, "仅管理员可查看AI审计日志");
            }
        }
        return Result.success(aiAuditLogService.listRecent(limit));
    }
}
