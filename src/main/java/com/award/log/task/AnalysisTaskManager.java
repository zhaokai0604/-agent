package com.award.log.task;

import com.award.log.common.PageResult;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.mapper.LogAnalysisTaskMapper;
import com.award.log.model.LogAnalysisDetail;
import com.award.log.model.LogAnalysisTask;
import com.award.log.model.LogProtocolType;
import com.award.log.model.LogSeverityLevel;
import com.award.log.util.HistoryFilterTime;
import com.award.log.util.LogAnalysisArtifactCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 日志分析任务管理器：负责任务生命周期、分页查询与明细落库。
 * 终态（COMPLETED/FAILED/CANCELLED）禁止被进度/失败/取消覆盖；删除时级联明细与产物。
 */
@Slf4j
@Component
public class AnalysisTaskManager {

    @Autowired
    private LogAnalysisTaskMapper taskMapper;

    @Autowired
    private LogAnalysisDetailMapper detailMapper;

    @Autowired
    private LogAnalysisArtifactCleaner artifactCleaner;

    @Autowired
    private DurableLogTaskStore durableTasks;

    public void prepareInput(String taskId, java.io.File input) {
        if (durableTasks != null) durableTasks.stage(taskId, input);
    }

    public DurableLogTaskStore.UploadReservation reserveUpload(int userId, String requestKey,
                                                                 java.io.File input, String candidateTaskId) {
        if (durableTasks == null) throw new IllegalStateException("Durable task store is unavailable");
        return durableTasks.reserveUpload(userId, requestKey, input, candidateTaskId);
    }

    public void submissionFailed(String taskId) {
        try {
            if (durableTasks != null) durableTasks.recoveryRequired(taskId, "日志保存或分析排队失败，请恢复任务或重新上传日志");
            else failTask(taskId, "Analysis submission failed");
        } finally { artifactCleaner.deleteUploadTemps(taskId); }
    }

    public <T> T withTaskLock(String taskId, java.util.function.Supplier<T> action) {
        return durableTasks == null ? action.get() : durableTasks.locked(taskId, action);
    }

    @Value("${log.analysis.detail-batch-size:1000}")
    private int detailBatchSize;

    @Value("${log.analysis.report-anomaly-limit:200}")
    private int reportAnomalyLimit;

    @Value("${log.analysis.export-reload-limit:100000}")
    private int exportReloadLimit;

    public PageResult<TaskInfo> getTasksPage(int pageNum, int pageSize) {
        return getTasksPage(pageNum, pageSize, null, null, null, null);
    }

    public PageResult<TaskInfo> getTasksPage(int pageNum, int pageSize,
                                             String fileName, String status, String startTime, String endTime) {
        QueryWindow window = buildQueryWindow(pageNum, pageSize, fileName, status, startTime, endTime);
        long total = taskMapper.countWithFilter(window.fileName, window.status, window.createTimeStart, window.createTimeEnd);
        List<TaskInfo> list = taskMapper.selectPageWithFilter(
                        window.offset, window.pageSize, window.fileName, window.status,
                        window.createTimeStart, window.createTimeEnd)
                .stream()
                .map(task -> convertToDto(task, null))
                .collect(Collectors.toList());
        return new PageResult<>(list, total);
    }

    /** 非管理员仅能看到自身 userId 下的任务分页。 */
    public PageResult<TaskInfo> getTasksPageForUser(Integer userId, boolean admin, int pageNum, int pageSize,
                                                    String fileName, String status, String startTime, String endTime) {
        if (admin) {
            return getTasksPage(pageNum, pageSize, fileName, status, startTime, endTime);
        }
        if (userId == null) {
            return new PageResult<>(List.of(), 0L);
        }
        QueryWindow window = buildQueryWindow(pageNum, pageSize, fileName, status, startTime, endTime);
        long total = taskMapper.countWithFilterByUserId(
                userId, window.fileName, window.status, window.createTimeStart, window.createTimeEnd);
        List<TaskInfo> list = taskMapper.selectPageWithFilterByUserId(
                        window.offset, window.pageSize, userId, window.fileName, window.status,
                        window.createTimeStart, window.createTimeEnd)
                .stream()
                .map(task -> convertToDto(task, null))
                .collect(Collectors.toList());
        return new PageResult<>(list, total);
    }

    public TaskInfo initTask(String taskId, String fileName) {
        return initTask(taskId, null, fileName);
    }

    public TaskInfo initTask(String taskId, Integer userId, String fileName) {
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setUserId(userId);
        task.setFileName(fileName);
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setCurrentStep("Waiting for processing...");
        task.setCreateTime(LocalDateTime.now());
        taskMapper.insert(task);
        return convertToDto(task, null);
    }

    public TaskInfo getTask(String taskId) {
        LogAnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("Task not found: {}", taskId);
            return null;
        }
        return buildTaskInfo(task);
    }

    public TaskInfo getTaskForUser(String taskId, Integer userId, boolean admin) {
        if (admin) {
            return getTask(taskId);
        }
        if (userId == null) {
            return null;
        }
        LogAnalysisTask task = taskMapper.selectByIdAndUserId(taskId, userId);
        if (task == null) {
            return null;
        }
        return buildTaskInfo(task);
    }

    public List<TaskInfo> getAllTasks() {
        return taskMapper.selectAll().stream()
                .map(task -> convertToDto(task, null))
                .collect(Collectors.toList());
    }

    public boolean canAccessTask(String taskId, Integer userId, boolean admin) {
        if (admin) {
            return taskMapper.selectById(taskId) != null;
        }
        if (userId == null) {
            return false;
        }
        return taskMapper.selectByIdAndUserId(taskId, userId) != null;
    }

    @Transactional
    public void updateProgress(String taskId, int progress, String step) {
        if (durableTasks != null && !durableTasks.currentExecution(taskId)) return;
        LogAnalysisTask existing = taskMapper.selectById(taskId);
        if (existing != null) {
            String status = existing.getStatus();
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return;
            }
        }
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setProgress(progress);
        task.setStatus("PROCESSING");
        task.setCurrentStep(step);
        taskMapper.updateIfStatus(task, List.of("PENDING", "PROCESSING"));
        if (durableTasks != null) durableTasks.progress(taskId, step);
    }

    @Transactional(rollbackFor = Exception.class)
    /** 将任务标为 COMPLETED 并分批落库解析明细。 */
    public boolean completeTask(String taskId, List<EnhancedLogParseResultEntity> result, TaskInfo.TaskSummary summary) {
        if (durableTasks != null && !durableTasks.currentExecution(taskId)) return false;
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setProgress(100);
        task.setStatus("COMPLETED");
        if (summary != null && summary.isLineCapApplied()) {
            task.setCurrentStep("Analysis completed (truncated, skipped " + summary.getLinesSkipped() + " lines)");
        } else {
            task.setCurrentStep("Analysis completed");
        }
        if (summary != null) {
            task.setTotalLogs(summary.getTotalLogs());
            task.setAnomalyCount(summary.getAnomalyCount());
            task.setAnomalyRate(summary.getAnomalyRate());
            task.setCostTime(summary.getCostTime());
        }
        if (taskMapper.updateIfStatus(task, List.of("PENDING", "PROCESSING")) == 0) return false;

        if (result == null || result.isEmpty()) {
            if (durableTasks != null) durableTasks.finish(taskId, "COMPLETED");
            return true;
        }
        int batchSize = Math.max(100, detailBatchSize);
        List<LogAnalysisDetail> batch = new ArrayList<>(batchSize);
        for (EnhancedLogParseResultEntity entity : result) {
            batch.add(convertDetailToEntity(taskId, entity));
            if (batch.size() >= batchSize) {
                detailMapper.batchInsert(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            detailMapper.batchInsert(batch);
        }
        if (durableTasks != null) durableTasks.finish(taskId, "COMPLETED");
        return true;
    }

    @Transactional
    public boolean failTask(String taskId, String errorMsg) {
        if (durableTasks != null && !durableTasks.currentExecution(taskId)) return false;
        if (isTerminalDbStatus(taskId)) {
            log.warn("[任务状态] 忽略失败写入，任务已终态: {}", taskId);
            return false;
        }
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("FAILED");
        task.setErrorMsg(errorMsg);
        boolean updated = taskMapper.updateIfStatus(task, List.of("PENDING", "PROCESSING", "PAUSED")) > 0;
        if (updated && durableTasks != null) durableTasks.finish(taskId, "FAILED");
        return updated;
    }

    /** 用户取消：DB 写 CANCELLED，禁止覆盖 COMPLETED/FAILED */
    @Transactional
    public boolean cancelTask(String taskId, String message) {
        if (isTerminalDbStatus(taskId)) {
            log.warn("[任务状态] 忽略取消，任务已终态: {}", taskId);
            return false;
        }
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("CANCELLED");
        task.setErrorMsg(message != null ? message : "任务被用户取消");
        task.setCurrentStep("Cancelled by user");
        boolean updated = taskMapper.updateIfStatus(task, List.of("PENDING", "PROCESSING", "PAUSED", "RECOVERY_REQUIRED")) > 0;
        if (updated && durableTasks != null) durableTasks.finish(taskId, "CANCELLED");
        return updated;
    }

    public boolean pauseTask(String taskId) {
        if (isTerminalDbStatus(taskId)) {
            log.warn("[任务状态] 忽略暂停，任务已终态: {}", taskId);
            return false;
        }
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("PAUSED");
        task.setCurrentStep("Task paused");
        return taskMapper.updateIfStatus(task, List.of("PENDING", "PROCESSING")) > 0;
    }

    public boolean resumeTask(String taskId) {
        LogAnalysisTask existing = taskMapper.selectById(taskId);
        if (existing == null) {
            return false;
        }
        String st = existing.getStatus();
        if ("COMPLETED".equals(st) || "FAILED".equals(st) || "CANCELLED".equals(st)) {
            log.warn("[任务状态] 忽略恢复，任务已终态: {} status={}", taskId, st);
            return false;
        }
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("PROCESSING");
        task.setCurrentStep("Task resumed");
        return taskMapper.updateIfStatus(task, List.of("PAUSED")) > 0;
    }

    /** 已完成/失败/取消视为终态，后续状态写入应忽略。 */
    public boolean isTerminalDbStatus(String taskId) {
        LogAnalysisTask existing = taskMapper.selectById(taskId);
        if (existing == null || existing.getStatus() == null) {
            return false;
        }
        String st = existing.getStatus();
        return "COMPLETED".equals(st) || "FAILED".equals(st) || "CANCELLED".equals(st);
    }

    public Integer getTaskOwner(String taskId) {
        LogAnalysisTask task = taskMapper.selectById(taskId);
        return task == null ? null : task.getUserId();
    }

    public String getDbStatus(String taskId) {
        LogAnalysisTask existing = taskMapper.selectById(taskId);
        return existing == null ? null : existing.getStatus();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTask(String taskId) {
        return withTaskLock(taskId, () -> deleteLocked(taskId));
    }

    private boolean deleteLocked(String taskId) {
        try {
            detailMapper.deleteByTaskId(taskId);
            int taskCount = taskMapper.deleteById(taskId);
            if (durableTasks != null) durableTasks.markDeleted(taskId, () -> artifactCleaner.purgeTaskArtifacts(taskId));
            else artifactCleaner.purgeTaskArtifacts(taskId);
            return taskCount > 0;
        } catch (Exception e) {
            log.error("Failed to delete task {}", taskId, e);
            throw new RuntimeException("Delete task failed", e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTaskForUser(String taskId, Integer userId, boolean admin) {
        if (!canAccessTask(taskId, userId, admin)) {
            return false;
        }
        return deleteTask(taskId);
    }

    private QueryWindow buildQueryWindow(int pageNum, int pageSize,
                                         String fileName, String status, String startTime, String endTime) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.max(pageSize, 1);
        int offset = (safePage - 1) * safeSize;
        String safeFileName = blankToNull(fileName);
        String safeStatus = blankToNull(status);
        String safeStartTime = blankToNull(startTime);
        String safeEndTime = blankToNull(endTime);
        LocalDate startDate = HistoryFilterTime.parseDateOnly(safeStartTime);
        LocalDate endDate = HistoryFilterTime.parseDateOnly(safeEndTime);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            LocalDate tmp = startDate;
            startDate = endDate;
            endDate = tmp;
        }
        LocalDateTime createTimeStart = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime createTimeEnd = endDate == null ? null : endDate.atTime(23, 59, 59);
        return new QueryWindow(offset, safeSize, safeFileName, safeStatus, createTimeStart, createTimeEnd);
    }

    private TaskInfo buildTaskInfo(LogAnalysisTask task) {
        List<EnhancedLogParseResultEntity> results = null;
        TaskInfo.TaskSummary extraSummaryBits = null;
        if ("COMPLETED".equals(task.getStatus())) {
            int limit = Math.max(20, reportAnomalyLimit);
            List<LogAnalysisDetail> anomalies = detailMapper.selectAnomaliesByTaskId(task.getTaskId(), limit);
            results = anomalies.stream().map(this::convertDetailToDto).collect(Collectors.toList());
            long detailTotal = detailMapper.countByTaskId(task.getTaskId());
            extraSummaryBits = new TaskInfo.TaskSummary();
            extraSummaryBits.setDetailTotal(detailTotal);
            extraSummaryBits.setResultReturned(results.size());
            extraSummaryBits.setResultTruncated(detailTotal > results.size());
            Map<String, Integer> severityCounts = new LinkedHashMap<>();
            List<Map<String, Object>> dist = detailMapper.selectSeverityDistributionByTaskId(task.getTaskId());
            if (dist != null) {
                for (Map<String, Object> row : dist) {
                    if (row == null) continue;
                    Object sev = row.get("severity");
                    if (sev == null) {
                        sev = row.get("SEVERITY");
                    }
                    Object cnt = row.get("cnt");
                    if (cnt == null) {
                        cnt = row.get("CNT");
                    }
                    if (sev != null && cnt != null) {
                        severityCounts.put(String.valueOf(sev), ((Number) cnt).intValue());
                    }
                }
            }
            extraSummaryBits.setSeverityCounts(severityCounts);
        }
        TaskInfo info = convertToDto(task, results);
        if (extraSummaryBits != null && info.getSummary() != null) {
            info.getSummary().setDetailTotal(extraSummaryBits.getDetailTotal());
            info.getSummary().setResultReturned(extraSummaryBits.getResultReturned());
            info.getSummary().setResultTruncated(extraSummaryBits.isResultTruncated());
            info.getSummary().setSeverityCounts(extraSummaryBits.getSeverityCounts());
        }
        if (info.getSummary() != null && task.getCurrentStep() != null
                && task.getCurrentStep().contains("truncated")) {
            info.getSummary().setLineCapApplied(true);
            // skipped N lines
            try {
                String step = task.getCurrentStep();
                int idx = step.indexOf("skipped ");
                if (idx >= 0) {
                    String rest = step.substring(idx + 8).trim();
                    int end = 0;
                    while (end < rest.length() && Character.isDigit(rest.charAt(end))) end++;
                    if (end > 0) {
                        info.getSummary().setLinesSkipped(Integer.parseInt(rest.substring(0, end)));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return info;
    }

    /** 分页拉取任务明细（前端大数据场景） */
    public PageResult<EnhancedLogParseResultEntity> getTaskDetailsPage(String taskId, int pageNum, int pageSize,
                                                                       boolean anomalyOnly) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        int offset = (safePage - 1) * safeSize;
        long total = anomalyOnly
                ? detailMapper.countAnomaliesByTaskId(taskId)
                : detailMapper.countByTaskId(taskId);
        List<LogAnalysisDetail> page = detailMapper.selectByTaskIdPage(taskId, offset, safeSize, anomalyOnly);
        PageResult<EnhancedLogParseResultEntity> result = new PageResult<>();
        result.setList(page.stream().map(this::convertDetailToDto).collect(Collectors.toList()));
        result.setTotal(total);
        return result;
    }

    /** 导出产物缺失时，从 DB 有限重载明细（禁止无界） */
    public List<EnhancedLogParseResultEntity> loadDetailsForExport(String taskId) {
        int limit = Math.max(1000, exportReloadLimit);
        List<LogAnalysisDetail> details = detailMapper.selectByTaskIdPage(taskId, 0, limit, false);
        return details.stream().map(this::convertDetailToDto).collect(Collectors.toList());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private TaskInfo convertToDto(LogAnalysisTask entity, List<EnhancedLogParseResultEntity> results) {
        TaskInfo info = new TaskInfo();
        info.setTaskId(entity.getTaskId());
        info.setUserId(entity.getUserId());
        info.setFileName(entity.getFileName());
        info.setStatus(entity.getStatus());
        info.setProgress(entity.getProgress() == null ? 0 : entity.getProgress());
        info.setCurrentStep(entity.getCurrentStep());
        info.setErrorMsg(entity.getErrorMsg());
        info.setAiDiagnosis(entity.getAiDiagnosis());
        info.setCreateTime(entity.getCreateTime());
        if (durableTasks != null) info.setRuntime(durableTasks.snapshot(entity.getTaskId()));

        TaskInfo.TaskSummary summary = new TaskInfo.TaskSummary();
        summary.setTotalLogs(entity.getTotalLogs() == null ? 0 : entity.getTotalLogs());
        summary.setAnomalyCount(entity.getAnomalyCount() == null ? 0 : entity.getAnomalyCount());
        summary.setAnomalyRate(entity.getAnomalyRate() == null ? 0.0 : entity.getAnomalyRate());
        summary.setCostTime(entity.getCostTime() == null ? 0L : entity.getCostTime());
        info.setSummary(summary);
        info.setResult(results);
        return info;
    }

    private LogAnalysisDetail convertDetailToEntity(String taskId, EnhancedLogParseResultEntity dto) {
        LogAnalysisDetail entity = new LogAnalysisDetail();
        entity.setTaskId(taskId);
        entity.setLogTime(dto.getLogTime());
        entity.setSeverity(dto.getSeverity() != null ? dto.getSeverity().name() : null);
        entity.setProtocol(dto.getProtocol() != null ? dto.getProtocol().name() : null);
        entity.setPid(dto.getPid());
        entity.setIsAnomaly(dto.isAnomaly());
        entity.setAnomalyScore(dto.getAnomalyScore());
        entity.setDesensitizedLog(dto.getDesensitizedLog());
        entity.setTemplateId(dto.getTemplateId());
        if (dto.getAnomalyReasons() != null && !dto.getAnomalyReasons().isEmpty()) {
            entity.setAnomalyReasons(String.join(",", dto.getAnomalyReasons()));
        }
        return entity;
    }

    private EnhancedLogParseResultEntity convertDetailToDto(LogAnalysisDetail entity) {
        EnhancedLogParseResultEntity dto = new EnhancedLogParseResultEntity(entity.getDesensitizedLog());
        dto.setLogTime(entity.getLogTime());
        dto.setPid(entity.getPid());
        dto.setAnomaly(entity.getIsAnomaly() != null && entity.getIsAnomaly());
        dto.setAnomalyScore(entity.getAnomalyScore() != null ? entity.getAnomalyScore() : 0.0);
        dto.setTemplateId(entity.getTemplateId());
        try {
            if (entity.getSeverity() != null) {
                dto.setSeverity(LogSeverityLevel.valueOf(entity.getSeverity()));
            }
            if (entity.getProtocol() != null) {
                dto.setProtocol(LogProtocolType.valueOf(entity.getProtocol()));
            }
        } catch (Exception ignored) {
        }
        if (entity.getAnomalyReasons() != null) {
            for (String reason : entity.getAnomalyReasons().split(",")) {
                dto.getAnomalyReasons().add(reason);
            }
        }
        return dto;
    }

    private record QueryWindow(int offset, int pageSize, String fileName, String status,
                               LocalDateTime createTimeStart, LocalDateTime createTimeEnd) {
    }
}
