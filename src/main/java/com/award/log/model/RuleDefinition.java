package com.award.log.model;

import lombok.Data;

@Data
public class RuleDefinition {
    private String id;
    private String name;
    private String expression;
    private String severity;
    private boolean enabled;
}
