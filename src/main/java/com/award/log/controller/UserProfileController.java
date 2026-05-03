package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.SysUser;
import com.award.log.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户管理控制器
 */
@Slf4j
@Tag(name = "User Management")
@RestController
@RequestMapping("/api/profile")
public class UserProfileController {

    @Autowired
    private SysUserService sysUserService;

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
}
