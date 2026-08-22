package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 决策引擎一次判定记录，对应 decision_log 表。
 */
@Data
public class DecisionLog {
    private Long id;
    private String decisionId;
    private String engineType;
    private Integer shouldAlert;
    private Double confidence;
    private Long latencyMs;
    private String inputJson;
    private String outputJson;
    private String traceJson;
    private LocalDateTime createTime;
}
