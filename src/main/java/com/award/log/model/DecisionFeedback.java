package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

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
}
