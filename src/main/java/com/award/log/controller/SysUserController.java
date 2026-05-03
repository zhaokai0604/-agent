package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.SysUser;
import com.award.log.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.award.log.security.AuthInterceptor;

/**
 * 系统用户Controller
 */
@Slf4j
@Tag(name = "System User")
@RestController
@RequestMapping("/admin/user")
public class SysUserController {

    @Autowired
    private SysUserService sysUserService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<SysUser> login(@RequestBody SysUser loginUser, HttpSession session) {
        log.info("用户登录: {}", loginUser.getUsername());
        SysUser user = sysUserService.login(loginUser.getUsername(), loginUser.getPassword());
        if (user != null) {
            session.setAttribute(AuthInterceptor.SESSION_USER_ID, user.getUserId());
            session.setAttribute(AuthInterceptor.SESSION_USER_ROLE, user.getRole());
            user.setPassword(null);
            return Result.success(user);
        } else {
            return Result.error("用户名或密码错误");
        }
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Boolean> logout(HttpSession session) {
        session.invalidate();
        return Result.success(true);
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<Integer> register(@RequestBody SysUser user) {
        log.info("用户注册: {}", user.getUsername());
        Integer userId = sysUserService.registerUser(user);
        if (userId != null) {
            return Result.success(userId);
        } else {
            return Result.error("用户名已存在");
        }
    }

    @Operation(summary = "获取用户信息")
    @GetMapping("/{userId}")
    public Result<SysUser> getUserById(@PathVariable String userId) {
        log.info("获取用户信息: {}", userId);
        try {
            // 处理"null"和"undefined"等特殊情况
            if ("null".equals(userId) || "undefined".equals(userId)) {
                return Result.error("用户ID不能为空");
            }
            Integer id = Integer.parseInt(userId);
            SysUser user = sysUserService.getUserById(id);
            return Result.success(user);
        } catch (NumberFormatException e) {
            log.error("用户ID格式错误: {}", userId, e);
            return Result.error("用户ID格式错误");
        }
    }

    @Operation(summary = "更新用户信息")
    @PutMapping
    public Result<Boolean> updateUser(@RequestBody SysUser user) {
        log.info("更新用户信息: {}", user);
        try {
            if (user == null) {
                return Result.error("用户信息不能为空");
            }
            if (user.getUserId() == null) {
                return Result.error("用户ID不能为空");
            }
            boolean result = sysUserService.updateUser(user);
            return Result.success(result);
        } catch (Exception e) {
            log.error("更新用户信息失败: {}", user, e);
            return Result.error("更新用户信息失败: " + e.getMessage());
        }
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{userId}")
    public Result<Boolean> deleteUser(@PathVariable String userId) {
        log.info("删除用户: {}", userId);
        try {
            // 处理"null"和"undefined"等特殊情况
            if ("null".equals(userId) || "undefined".equals(userId)) {
                return Result.error("用户ID不能为空");
            }
            Integer id = Integer.parseInt(userId);
            boolean result = sysUserService.deleteUser(id);
            return Result.success(result);
        } catch (NumberFormatException e) {
            log.error("用户ID格式错误: {}", userId, e);
            return Result.error("用户ID格式错误");
        }
    }

    @Operation(summary = "获取所有用户")
    @GetMapping("/list")
    public Result<java.util.List<SysUser>> getAllUsers() {
        log.info("获取所有用户");
        java.util.List<SysUser> users = sysUserService.getAllUsers();
        return Result.success(users);
    }

    @Operation(summary = "分页获取用户列表")
    @GetMapping("/page")
    public Result<com.award.log.common.PageResult<SysUser>> getUsersPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        log.info("分页获取用户列表，页码: {}, 每页条数: {}", pageNum, pageSize);
        com.award.log.common.PageResult<SysUser> pageResult = sysUserService.getUsersPage(pageNum, pageSize);
        return Result.success(pageResult);
    }

    @Operation(summary = "检查用户是否存在")
    @PostMapping("/check-user")
    public Result<Boolean> checkUserExists(@RequestBody java.util.Map<String, String> userInfo) {
        String username = userInfo.get("username");
        log.info("检查用户是否存在: {}", username);
        boolean exists = sysUserService.userExists(username);
        return Result.success(exists);
    }

    @Operation(summary = "重置密码")
    @PostMapping("/reset-password")
    public Result<Boolean> resetPassword(@RequestBody java.util.Map<String, String> passwordInfo) {
        String username = passwordInfo.get("username");
        String newPassword = passwordInfo.get("newPassword");
        log.info("重置用户密码: {}", username);
        boolean success = sysUserService.resetPassword(username, newPassword);
        if (success) {
            return Result.success(true);
        } else {
            return Result.error("重置密码失败");
        }
    }

    @Operation(summary = "更新用户角色")
    @PostMapping("/{userId}/role")
    public Result<Boolean> updateUserRole(@PathVariable String userId, @RequestBody java.util.Map<String, Integer> roleInfo) {
        log.info("更新用户角色: 用户ID={}, 角色信息={}", userId, roleInfo);
        try {
            // 处理"null"和"undefined"等特殊情况
            if ("null".equals(userId) || "undefined".equals(userId)) {
                return Result.error("用户ID不能为空");
            }
            Integer id = Integer.parseInt(userId);
            Integer role = roleInfo.get("role");
            if (role == null) {
                return Result.error("角色信息不能为空");
            }
            boolean success = sysUserService.updateUserRole(id, role);
            if (success) {
                return Result.success(true);
            } else {
                return Result.error("更新用户角色失败");
            }
        } catch (NumberFormatException e) {
            log.error("用户ID格式错误: {}", userId, e);
            return Result.error("用户ID格式错误");
        } catch (Exception e) {
            log.error("更新用户角色失败: 用户ID={}", userId, e);
            return Result.error("更新用户角色失败: " + e.getMessage());
        }
    }
}