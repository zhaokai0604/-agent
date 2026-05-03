package com.award.log.decision;

import com.award.log.collector.model.RawLogEvent;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DecisionInput {
    private RawLogEvent event;
    private String template;
    private double errorRate1m;
    private int total1m;
    private int error1m;
}
