package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型评估结果（accuracy/precision/recall 等），对应 model_evaluation 表。
 */
@Data
public class ModelEvaluation {
    private Long id;
    private String modelVersion;
    private Integer sampleSize;
    private Double accuracy;
    private Double precisionScore;
    private Double recallScore;
    private Double f1Score;
    private Double rocAuc;
    private Double prAuc;
    private String confusionMatrix;
    private LocalDateTime createTime;
}
