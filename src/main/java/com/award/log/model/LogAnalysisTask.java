package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日志文件分析任务（进度、状态、异常统计），对应 log_analysis_task 表。
 */
@Data
public class LogAnalysisTask {
    private String taskId;
    private Integer userId;
    private String fileName;
    private String status;
    private Integer progress;
    private String currentStep;
    private String errorMsg;
    private String aiDiagnosis;
    private Integer totalLogs;
    private Integer anomalyCount;
    private Double anomalyRate;
    private Long costTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
