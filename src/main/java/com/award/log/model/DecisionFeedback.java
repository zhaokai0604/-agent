package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 决策人工反馈与训练样本，对应 decision_feedback 表。
 */
@Data
public class DecisionFeedback {
    private Long id;
    private String decisionId;
    private Integer actualAlert;
    private String reviewer;
    private String remark;
    private LocalDateTime createTime;

    private String logContent;
    private String logLevel;
    private String logTemplate;
    private Double modelConfidence;
    private Boolean isTrained;

    /** 与 RfFeatureVectorExt 17–19 / 5–8 对齐的窗口特征 */
    private Double errorRate1m;
    private Double error1m;
    private Double total1m;
    private Double intervalMs;
}
