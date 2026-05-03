package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.SysUser;
import com.award.log.security.AuthInterceptor;
import com.award.log.service.SysUserService;
import com.award.log.service.impl.AiAuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 个人中心 API（与前端 {@code /api/profile/**} 对齐）。
 */
@Slf4j
@Tag(name = "User Profile")
@RestController
@RequestMapping("/api/profile")
public class UserProfileController {

    private static final String SESSION_API_KEY = "PROFILE_API_KEY";
    private static final String SESSION_NOTIFICATION = "PROFILE_NOTIFICATION_SETTINGS";

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private AiAuditLogService aiAuditLogService;

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/user-info")
    public Result<SysUser> getCurrentUserInfo(@RequestParam(required = false) Integer userId) {
        log.info("获取当前用户信息: {}", userId);
        SysUser user;
        if (userId != null) {
            user = sysUserService.getUserById(userId);
        } else {
            // 如果没有传递userId，先尝试获取管理员用户
            user = sysUserService.getAdminUser();
            // 如果没有管理员用户，获取第一个用户
            if (user == null) {
                log.info("未找到管理员用户，尝试获取第一个用户");
                user = sysUserService.getAllUsers().stream().findFirst().orElse(null);
            }
        }
        if (user != null) {
            return Result.success(user);
        } else {
            return Result.error("获取用户信息失败");
        }
    }

    @Operation(summary = "更新用户信息")
    @PutMapping("/user-info")
    public Result<Boolean> updateUserInfo(@RequestBody SysUser user) {
        log.info("更新用户信息: {}", user.getUserId());
        if (user.getUserId() == null) {
            return Result.error("用户ID不能为空");
        }
        boolean result = sysUserService.updateUser(user);
        return Result.success(result);
    }

    @Operation(summary = "修改密码")
    @PostMapping("/change-password")
    public Result<Boolean> changePassword(@RequestParam(required = false) Integer userId, @RequestBody Map<String, String> passwordInfo) {
        String oldPassword = passwordInfo.get("oldPassword");
        String newPassword = passwordInfo.get("newPassword");
        String confirmPassword = passwordInfo.get("confirmPassword");

        if (!newPassword.equals(confirmPassword)) {
            return Result.error("两次输入的密码不一致");
        }

        SysUser user;
        if (userId != null) {
            user = sysUserService.getUserById(userId);
        } else {
            // 如果没有传递userId，获取管理员用户
            user = sysUserService.getAdminUser();
        }
        if (user == null) {
            return Result.error("获取用户信息失败");
        }

        // 验证旧密码
        // 实际项目中应使用密码加密器验证，这里直接比较
        if (!oldPassword.equals(user.getPassword())) {
            return Result.error("旧密码错误");
        }

        user.setPassword(newPassword);
        boolean result = sysUserService.updateUser(user);
        return Result.success(result);
    }

    @Operation(summary = "登录与接口访问足迹（来源 ai_audit_log）")
    @GetMapping("/login-history")
    public Result<List<Map<String, Object>>> loginHistory(HttpSession session,
                                                          @RequestParam(defaultValue = "50") int limit) {
        Object uid = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return Result.success(aiAuditLogService.listRecentByUserId(String.valueOf(uid), limit));
    }

    @Operation(summary = "生成演示用 API Key（仅存当前会话）")
    @PostMapping("/generate-api-key")
    public Result<Map<String, String>> generateApiKey(HttpSession session) {
        Object uid = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        String key = "ak_" + UUID.randomUUID().toString().replace("-", "");
        session.setAttribute(SESSION_API_KEY, key);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("apiKey", key);
        body.put("userId", String.valueOf(uid));
        return Result.success(body);
    }

    @Operation(summary = "通知设置（会话内）")
    @GetMapping("/notification-settings")
    public Result<Map<String, Object>> getNotificationSettings(HttpSession session) {
        if (session.getAttribute(AuthInterceptor.SESSION_USER_ID) == null) {
            return Result.error(401, "未登录");
        }
        return Result.success(effectiveNotification(session));
    }

    @Operation(summary = "更新通知设置（会话内合并）")
    @PutMapping("/notification-settings")
    public Result<Map<String, Object>> updateNotificationSettings(HttpSession session,
                                                                  @RequestBody(required = false) Map<String, Object> body) {
        if (session.getAttribute(AuthInterceptor.SESSION_USER_ID) == null) {
            return Result.error(401, "未登录");
        }
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(effectiveNotification(session));
        if (body != null) {
            body.forEach((k, v) -> {
                if (k != null && !k.isBlank()) {
                    merged.put(k, v);
                }
            });
        }
        session.setAttribute(SESSION_NOTIFICATION, merged);
        return Result.success(merged);
    }

    @Operation(summary = "用户简要统计")
    @GetMapping("/user-stats")
    public Result<Map<String, Object>> userStats(HttpSession session) {
        Object uid = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        String userId = String.valueOf(uid);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("auditRequestCount", aiAuditLogService.countByUserId(userId));
        stats.put("hasApiKey", session.getAttribute(SESSION_API_KEY) != null);
        return Result.success(stats);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> effectiveNotification(HttpSession session) {
        Object raw = session.getAttribute(SESSION_NOTIFICATION);
        LinkedHashMap<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("emailEnabled", false);
        defaults.put("smsEnabled", false);
        defaults.put("taskAlerts", true);
        if (raw instanceof Map) {
            defaults.putAll((Map<String, Object>) raw);
        }
        return defaults;
    }
}
