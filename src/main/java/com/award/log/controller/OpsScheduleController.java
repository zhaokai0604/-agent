package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.AuthInterceptor;
import com.award.log.service.OpsDeferredTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "OpsSchedule")
@RestController
@RequestMapping("/api/ops-schedule")
@RequiredArgsConstructor
public class OpsScheduleController {

    private final OpsDeferredTaskService opsDeferredTaskService;

    @Operation(summary = "创建延时运维任务（支持全部已注册本地工具）")
    @PostMapping("/tasks")
    public Result<Map<String, Object>> createTask(HttpSession session, @RequestBody Map<String, Object> body) {
        try {
            Object dm = body.get("delayMinutes");
            if (dm == null) {
                return Result.error("delayMinutes 不能为空");
            }
            int delayMinutes = dm instanceof Number ? ((Number) dm).intValue() : Integer.parseInt(String.valueOf(dm).trim());
            String toolName = String.valueOf(body.getOrDefault("toolName", "")).trim();
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) body.get("parameters");

            Object uid = session == null ? null : session.getAttribute(AuthInterceptor.SESSION_USER_ID);
            String operatorId = uid != null ? String.valueOf(uid) : "";

            Map<String, Object> data = opsDeferredTaskService.schedule(delayMinutes, toolName, parameters, operatorId);
            return Result.success(data, "已创建延时任务");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("创建延时任务失败", e);
            return Result.error("创建失败: " + e.getMessage());
        }
    }

    @Operation(summary = "待执行的延时任务列表")
    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> listTasks() {
        return Result.success(opsDeferredTaskService.listPending());
    }

    @Operation(summary = "取消延时任务")
    @DeleteMapping("/tasks/{taskId}")
    public Result<Boolean> cancelTask(@PathVariable String taskId) {
        boolean ok = opsDeferredTaskService.cancel(taskId);
        return ok ? Result.success(true, "已取消") : Result.error(404, "任务不存在或已执行");
    }
}
