package com.award.log.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class LogAnalysisTask {
    private String taskId;      // 任务唯一ID
    private String fileName;    // 分析的原始文件名
    private String status;      // 任务状态
    private Integer progress;   // 进度百分比
    private String currentStep; // 当前处理阶段
    private String errorMsg;    // 错误信息描述
    private String aiDiagnosis; // AI 诊断结果全文
    
    // 统计分析结果汇总
    private Integer totalLogs;      // 日志总行数
    private Integer anomalyCount;   // 异常日志总数
    private Double anomalyRate;     // 异常占比 (0.0 - 1.0)
    private Long costTime;          // 解析耗时 (ms)
    
    private LocalDateTime createTime; // 创建时间
    private LocalDateTime updateTime; // 最后更新时间
}
