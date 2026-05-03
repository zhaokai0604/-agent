package com.award.log.service;

import com.award.log.controller.McpExecuteController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 延时执行本地工具（与 MCP 控制器同源逻辑；进程内调度，重启后任务丢失）。
 */
@Slf4j
@Service
public class OpsDeferredTaskService {

    private static final Set<String> ALLOWED_DEFERRED_TOOLS = Set.of(
            "DiskTool", "DiskInsightTool", "ProcessTool", "SystemLoadTool", "LogAnalysisTool",
            "CleanTempTool", "ConfigCheckTool", "NetworkTool", "PrivilegeTool", "OsInsightTool");

    private static final int MIN_DELAY_MIN = 1;
    private static final int MAX_DELAY_MIN = 24 * 60;

    private final ThreadPoolTaskScheduler scheduler;
    private final McpExecuteController mcpExecuteController;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskMeta> metas = new ConcurrentHashMap<>();

    public OpsDeferredTaskService(
            @Qualifier("opsDeferredTaskScheduler") ThreadPoolTaskScheduler scheduler,
            McpExecuteController mcpExecuteController) {
        this.scheduler = scheduler;
        this.mcpExecuteController = mcpExecuteController;
    }

    public Map<String, Object> schedule(int delayMinutes, String toolName, Map<String, Object> parameters,
                                         String operatorId) {
        validate(delayMinutes, toolName, parameters);

        String taskId = UUID.randomUUID().toString();
        Instant executeAt = Instant.now().plus(delayMinutes, ChronoUnit.MINUTES);
        Map<String, Object> paramCopy = parameters == null
                ? new HashMap<>()
                : new HashMap<>(parameters);

        TaskMeta meta = new TaskMeta(taskId, toolName, executeAt, paramCopy,
                operatorId == null ? "" : operatorId, "PENDING");
        metas.put(taskId, meta);

        ScheduledFuture<?> future = scheduler.schedule(() -> runTask(taskId), executeAt);
        futures.put(taskId, future);

        log.info("【延时运维】已创建任务 taskId={} tool={} executeAt={} delayMin={} operator={}",
                taskId, toolName, executeAt, delayMinutes, operatorId);

        return toView(meta);
    }

    public boolean cancel(String taskId) {
        ScheduledFuture<?> f = futures.get(taskId);
        if (f == null) {
            return false;
        }
        boolean cancelled = f.cancel(false);
        futures.remove(taskId);
        metas.remove(taskId);
        log.info("【延时运维】取消任务 taskId={} cancelled={}", taskId, cancelled);
        return cancelled;
    }

    public List<Map<String, Object>> listPending() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TaskMeta m : metas.values()) {
            if ("PENDING".equals(m.status)) {
                list.add(toView(m));
            }
        }
        list.sort((a, b) -> String.valueOf(a.get("executeAt")).compareTo(String.valueOf(b.get("executeAt"))));
        return list;
    }

    private void validate(int delayMinutes, String toolName, Map<String, Object> parameters) {
        if (delayMinutes < MIN_DELAY_MIN || delayMinutes > MAX_DELAY_MIN) {
            throw new IllegalArgumentException("延时必须在 " + MIN_DELAY_MIN + "~" + MAX_DELAY_MIN + " 分钟之间");
        }
        if (toolName == null || toolName.isBlank() || !ALLOWED_DEFERRED_TOOLS.contains(toolName)) {
            throw new IllegalArgumentException("不支持延时执行的工具: " + toolName);
        }
        if ("CleanTempTool".equals(toolName)) {
            Boolean dryRun = getBoolean(parameters, "dryRun");
            Boolean confirmDelete = getBoolean(parameters, "confirmDelete");
            if (Boolean.FALSE.equals(dryRun) && !Boolean.TRUE.equals(confirmDelete)) {
                throw new IllegalArgumentException("真实删除必须同时设置 dryRun=false 且 confirmDelete=true");
            }
        }
    }

    private void runTask(String taskId) {
        TaskMeta meta = metas.get(taskId);
        if (meta == null) {
            return;
        }
        meta.status = "RUNNING";
        try {
            log.info("【延时运维】开始执行 taskId={} tool={}", taskId, meta.toolName);
            Map<String, Object> exec = mcpExecuteController.executeDeferredScheduledTool(meta.toolName, meta.parameters);
            meta.status = Boolean.TRUE.equals(exec.get("success")) ? "DONE" : "FAILED";
            log.info("【延时运维】结束 taskId={} success={}", taskId, exec.get("success"));
        } catch (Exception e) {
            log.error("【延时运维】执行异常 taskId={}", taskId, e);
            meta.status = "FAILED";
        } finally {
            futures.remove(taskId);
            metas.remove(taskId);
        }
    }

    private Map<String, Object> toView(TaskMeta m) {
        Map<String, Object> map = new HashMap<>();
        map.put("taskId", m.taskId);
        map.put("toolName", m.toolName);
        map.put("executeAt", m.executeAt.toString());
        map.put("status", m.status);
        map.put("parameters", Collections.unmodifiableMap(new HashMap<>(m.parameters)));
        map.put("createdBy", m.createdBy);
        return map;
    }

    private static Boolean getBoolean(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object v = params.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return Boolean.parseBoolean(v.toString());
    }

    private static final class TaskMeta {
        final String taskId;
        final String toolName;
        final Instant executeAt;
        final Map<String, Object> parameters;
        final String createdBy;
        volatile String status;

        TaskMeta(String taskId, String toolName, Instant executeAt, Map<String, Object> parameters,
                 String createdBy, String status) {
            this.taskId = taskId;
            this.toolName = toolName;
            this.executeAt = executeAt;
            this.parameters = parameters;
            this.createdBy = createdBy;
            this.status = status;
        }
    }
}
