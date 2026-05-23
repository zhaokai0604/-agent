package com.award.log.task;

import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.mapper.LogAnalysisTaskMapper;
import com.award.log.model.LogAnalysisDetail;
import com.award.log.model.LogAnalysisTask;
import com.award.log.model.LogProtocolType;
import com.award.log.model.LogSeverityLevel;
import com.award.log.util.HistoryFilterTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AnalysisTaskManager {

    @Autowired
    private LogAnalysisTaskMapper taskMapper;

    @Autowired
    private LogAnalysisDetailMapper detailMapper;
    
    /**
     * 分页获取历史任务
     * @param pageNum 页码
     * @param pageSize 每页条数
     */
    public PageResult<TaskInfo> getTasksPage(int pageNum, int pageSize) {
        return getTasksPage(pageNum, pageSize, null, null, null, null);
    }
    
    /**
     * 分页获取历史任务（支持筛选）
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @param fileName 文件名（模糊匹配）
     * @param status 任务状态
     * @param startTime 开始时间
     * @param endTime 结束时间
     */
    public PageResult<TaskInfo> getTasksPage(int pageNum, int pageSize, String fileName, String status, String startTime, String endTime) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.max(pageSize, 1);
        int offset = (safePage - 1) * safeSize;
        fileName = blankToNull(fileName);
        status = blankToNull(status);
        startTime = blankToNull(startTime);
        endTime = blankToNull(endTime);
        java.time.LocalDate startDate = HistoryFilterTime.parseDateOnly(startTime);
        java.time.LocalDate endDate = HistoryFilterTime.parseDateOnly(endTime);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            java.time.LocalDate tmp = startDate;
            startDate = endDate;
            endDate = tmp;
        }
        java.time.LocalDateTime createTimeStart = startDate == null ? null : startDate.atStartOfDay();
        java.time.LocalDateTime createTimeEnd = endDate == null ? null : endDate.atTime(23, 59, 59);
        log.info("分页查询任务列表，pageNum: [{}], pageSize: [{}], fileName: [{}], status: [{}], createTimeStart: [{}], createTimeEnd: [{}]",
                safePage, safeSize, fileName, status, createTimeStart, createTimeEnd);

        long total = taskMapper.countWithFilter(fileName, status, createTimeStart, createTimeEnd);
        List<TaskInfo> list = taskMapper.selectPageWithFilter(offset, safeSize, fileName, status, createTimeStart, createTimeEnd).stream()
                .map(task -> convertToDto(task, null))
                .collect(Collectors.toList());
                
        return new PageResult<>(list, total);
    }
    
    /**
     * 初始化分析任务
     * @param taskId 任务ID
     * @param fileName 文件名
     * @return 初始化的任务信息
     */
    public TaskInfo initTask(String taskId, String fileName) {
        log.info("数据库初始化任务记录: [{}], 文件名: [{}]", taskId, fileName);
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setFileName(fileName);
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setCurrentStep("等待处理...");
        task.setCreateTime(LocalDateTime.now());
        taskMapper.insert(task);
        return convertToDto(task, null);
    }

    /**
     * 获取任务详细信息，包括已解析的日志详情
     * @param taskId 任务ID
     * @return 任务详情
     */
    public TaskInfo getTask(String taskId) {
        LogAnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务不存在: [{}]", taskId);
            return null;
        }

        List<EnhancedLogParseResultEntity> results = null;
        if ("COMPLETED".equals(task.getStatus())) {
            log.info("从数据库加载任务详情: [{}]", taskId);
            List<LogAnalysisDetail> details = detailMapper.selectByTaskId(taskId);
            results = details.stream().map(this::convertDetailToDto).collect(Collectors.toList());
            log.info("任务 [{}] 详情加载完成，共计 [{}] 条日志明细", taskId, results.size());
        }
        
        return convertToDto(task, results);
    }

    /**
     * 获取所有历史任务
     */
    public List<TaskInfo> getAllTasks() {
        return taskMapper.selectAll().stream()
                .map(task -> convertToDto(task, null))
                .collect(Collectors.toList());
    }

    /**
     * 更新任务进度
     */
    public void updateProgress(String taskId, int progress, String step) {
        log.debug("更新任务进度 - ID: [{}], 进度: [{}%], 当前步骤: [{}]", taskId, progress, step);
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setProgress(progress);
        task.setStatus("PROCESSING");
        task.setCurrentStep(step);
        taskMapper.updateById(task);
    }

    /**
     * 完成任务并保存日志明细
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(String taskId, List<EnhancedLogParseResultEntity> result, TaskInfo.TaskSummary summary) {
        log.info("任务分析完成，正在持久化结果到数据库: [{}]", taskId);
        // 1. 更新主任务
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setProgress(100);
        task.setStatus("COMPLETED");
        task.setCurrentStep("分析完成");
        if (summary != null) {
            task.setTotalLogs(summary.getTotalLogs());
            task.setAnomalyCount(summary.getAnomalyCount());
            task.setAnomalyRate(summary.getAnomalyRate());
            task.setCostTime(summary.getCostTime());
        }
        taskMapper.updateById(task);

        // 2. 批量保存详情
        if (result != null) {
            log.info("开始插入 [{}] 条日志明细记录...", result.size());
            final int batchSize = 500;
            List<LogAnalysisDetail> details = result.stream()
                    .map(entity -> convertDetailToEntity(taskId, entity))
                    .collect(Collectors.toList());
            for (int i = 0; i < details.size(); i += batchSize) {
                int end = Math.min(i + batchSize, details.size());
                detailMapper.batchInsert(details.subList(i, end));
            }
            log.info("日志明细持久化完成");
        }
    }

    /**
     * 更新 AI 诊断结果
     */
    public void updateAiDiagnosis(String taskId, String diagnosis) {
        log.info("更新任务 AI 诊断结果: [{}]", taskId);
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setAiDiagnosis(diagnosis);
        taskMapper.updateById(task);
    }

    /**
     * 标记任务失败
     */
    public void failTask(String taskId, String errorMsg) {
        log.error("任务失败 - ID: [{}], 错误原因: [{}]", taskId, errorMsg);
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("FAILED");
        task.setErrorMsg(errorMsg);
        taskMapper.updateById(task);
    }
    
    /**
     * 暂停任务
     */
    public void pauseTask(String taskId) {
        log.info("暂停任务 - ID: [{}]", taskId);
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("PAUSED");
        task.setCurrentStep("任务已暂停");
        taskMapper.updateById(task);
    }
    
    /**
     * 恢复任务
     */
    public void resumeTask(String taskId) {
        log.info("恢复任务 - ID: [{}]", taskId);
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setStatus("PROCESSING");
        task.setCurrentStep("恢复任务处理...");
        taskMapper.updateById(task);
    }
    
    /**
     * 删除任务（包括任务详情）
     * @param taskId 任务ID
     * @return 是否删除成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTask(String taskId) {
        log.info("开始删除任务 - ID: [{}]", taskId);
        try {
            // 1. 先删除任务详情
            int detailCount = detailMapper.deleteByTaskId(taskId);
            log.info("删除任务详情成功: {} 条", detailCount);
            
            // 2. 再删除任务本身
            int taskCount = taskMapper.deleteById(taskId);
            log.info("删除任务成功: {} 条", taskCount);
            
            return taskCount > 0;
        } catch (Exception e) {
            log.error("删除任务失败 - ID: [{}]", taskId, e);
            throw new RuntimeException("删除任务失败", e);
        }
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // --- Converters ---

    private TaskInfo convertToDto(LogAnalysisTask entity, List<EnhancedLogParseResultEntity> results) {
        TaskInfo info = new TaskInfo();
        info.setTaskId(entity.getTaskId());
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
        
        // Enum conversion
        try {
            if (entity.getSeverity() != null) dto.setSeverity(LogSeverityLevel.valueOf(entity.getSeverity()));
            if (entity.getProtocol() != null) dto.setProtocol(LogProtocolType.valueOf(entity.getProtocol()));
        } catch (Exception ignored) {}

        if (entity.getAnomalyReasons() != null) {
            for (String reason : entity.getAnomalyReasons().split(",")) {
                dto.getAnomalyReasons().add(reason);
            }
        }
        return dto;
    }
}
