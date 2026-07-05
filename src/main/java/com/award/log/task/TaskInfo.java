package com.award.log.task;

import com.award.log.dto.EnhancedLogParseResultEntity;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskInfo {
    private String taskId;
    private Integer userId;
    private String fileName;
    private String status;
    private int progress;
    private String currentStep;
    private String errorMsg;
    private List<EnhancedLogParseResultEntity> result;
    private String aiDiagnosis;
    private LocalDateTime createTime;
    private TaskSummary summary;

    @Data
    public static class TaskSummary {
        private int totalLogs;
        private int anomalyCount;
        private double anomalyRate;
        private long costTime;
    }
}
