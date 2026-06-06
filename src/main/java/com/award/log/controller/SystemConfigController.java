package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "System Config")
@RestController
@RequestMapping("/api/system-config")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;
    private final RequestUserResolver requestUserResolver;

    public SystemConfigController(SystemConfigService systemConfigService,
                                  RequestUserResolver requestUserResolver) {
        this.systemConfigService = systemConfigService;
        this.requestUserResolver = requestUserResolver;
    }

    @Operation(summary = "获取统一系统配置的当前有效视图")
    @GetMapping("/effective")
    public Result<Map<String, Object>> effective(HttpServletRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>(systemConfigService.getEffectiveConfig());
        payload.put("viewer", buildViewer(request));
        return Result.success(payload);
    }

    @Operation(summary = "保存统一系统配置")
    @PutMapping("/effective")
    public Result<Map<String, Object>> saveEffective(HttpServletRequest request,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "仅管理员可保存系统配置");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>(systemConfigService.saveEffectiveConfig(body == null ? Map.of() : body));
            payload.put("viewer", buildViewer(request));
            return Result.success(payload, "系统配置已保存");
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
    }

    private Map<String, Object> buildViewer(HttpServletRequest request) {
        Map<String, Object> viewer = new LinkedHashMap<>();
        viewer.put("userId", requestUserResolver.currentUserId(request));
        viewer.put("role", requestUserResolver.currentUserRole(request));
        viewer.put("editable", requestUserResolver.isAdmin(request));
        viewer.put("authMode", requestUserResolver.authMode(request));
        return viewer;
    }
}
