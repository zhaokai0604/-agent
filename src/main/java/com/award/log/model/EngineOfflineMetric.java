package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 决策引擎离线评估指标（误报/漏报与 precision/recall），对应 engine_offline_metric 表。
 */
@Data
public class EngineOfflineMetric {
    private Long id;
    private String engineType;
    private Integer sampleSize;
    private Integer falsePositive;
    private Integer falseNegative;
    private Double precisionScore;
    private Double recallScore;
    private Double f1Score;
    private LocalDateTime createTime;
}
