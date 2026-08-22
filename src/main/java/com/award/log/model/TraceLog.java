package com.award.log.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent/对话链路追踪快照（输入、风险、工具名与结果摘要），供 TraceService 落审计。
 */
@Data
@Builder
public class TraceLog {
    private String traceId;
    private String userInput;
    private String riskLevel;
    private String toolName;
    private String resultSummary;
    private long durationMs;
    private LocalDateTime timestamp;
}