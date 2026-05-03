package com.award.log.task;

import com.award.log.dto.EnhancedLogParseResultEntity;
import lombok.Data;
import java.util.List;
import java.time.LocalDateTime;

@Data
public class TaskInfo {
    private String taskId;
    private String fileName;
    // 状态: PENDING (等待中), PROCESSING (处理中), PAUSED (已暂停), COMPLETED (已完成), FAILED (失败)
    private String status; 
    // 任务执行进度: 0 - 100
    private int progress;
    // 当前正在进行的步骤描述
    private String currentStep;
    // 任务失败时的错误堆栈或简述
    private String errorMsg;
    // 解析后的日志结果列表
    private List<EnhancedLogParseResultEntity> result;
    // AI 自动生成的诊断报告文本
    private String aiDiagnosis;
    // 任务创建时间
    private LocalDateTime createTime;
    // 任务统计摘要信息
    private TaskSummary summary;

    @Data
    public static class TaskSummary {
        private int totalLogs;      // 日志总行数
        private int anomalyCount;   // 检出的异常行数
        private double anomalyRate; // 异常率
        private long costTime;      // 任务总耗时 (毫秒)
    }
}
