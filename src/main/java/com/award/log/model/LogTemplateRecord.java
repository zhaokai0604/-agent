package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Drain 日志模板记录（模板内容、使用次数），对应 log_template 表。
 */
@Data
public class LogTemplateRecord {
    private Long id;
    private String templateId;
    private String templateName;
    private String templateContent;
    private String severity;
    private Long useCount;
    private LocalDateTime lastSeenTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
