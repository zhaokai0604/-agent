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
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AnalysisTaskManager {

    @Autowired
    private LogAnalysisTaskMapper taskMapper;

    @Autowired
    private LogAnalysisDetailMapper detailMapper;

    @Autowired
    private LogAnalysisArtifactCleaner artifactCleaner;

    @Value("${log.analysis.detail-batch-size:1000}")
    private int detailBatchSize;

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

    public void updateProgress(String taskId, int progress, String step) {
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setProgress(progress);
        task.setStatus("PROCESSING");
        task.setCurrentStep(step);
        taskMapper.updateById(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeTask(String taskId, List<EnhancedLogParseResultEntity> result, TaskInfo.TaskSummary summary) {
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setProgress(100);
        task.setStatus("COMPLETED");
        task.setCurrentStep("Analysis completed");
        if (summary != null) {
            task.setTotalLogs(summary.getTotalLogs());
            task.setAnomalyCount(summary.getAnomalyCount());
            task.setAnomalyRate(summary.getAnomalyRate());
            task.setCostTime(summary.getCostTime());
        }
        taskMapper.updateById(task);

        if (result == null || result.isEmpty()) {
            return;
        }
        int batchSize = Math.max(100, detailBatchSize);
        List<LogAnalysisDetail> details = result.stream()
                .map(entity -> convertDetailToEntity(taskId, entity))
                .collect(Collectors.toList());
        for (int i = 0; i < details.size(); i += batchSize) {
            int end = Math.min(i + batchSize, details.size());
            detailMapper.batchInsert(details.subList(i, end));
        }
    }

    public void updateAiDiagnosis(String taskId, String diagnosis) {
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setAiDiagnosis(diagnosis);
        taskMapper.updateById(task);
    }

    public void failTask(String taskId, String errorMsg) {
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("FAILED");
        task.setErrorMsg(errorMsg);
        taskMapper.updateById(task);
    }

    public void pauseTask(String taskId) {
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("PAUSED");
        task.setCurrentStep("Task paused");
        taskMapper.updateById(task);
    }

    public void resumeTask(String taskId) {
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("PROCESSING");
        task.setCurrentStep("Task resumed");
        taskMapper.updateById(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTask(String taskId) {
        try {
            detailMapper.deleteByTaskId(taskId);
            int taskCount = taskMapper.deleteById(taskId);
            artifactCleaner.purgeTaskArtifacts(taskId);
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
        if ("COMPLETED".equals(task.getStatus())) {
            List<LogAnalysisDetail> details = detailMapper.selectByTaskId(task.getTaskId());
            results = details.stream().map(this::convertDetailToDto).collect(Collectors.toList());
        }
        return convertToDto(task, results);
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
