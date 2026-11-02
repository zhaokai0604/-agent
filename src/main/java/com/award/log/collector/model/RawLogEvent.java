package com.award.log.collector.model;

import lombok.Data;

/**
 * 采集层原始日志事件：尚未经过 Drain/解析的一行，带 source、offset、ingestTime 等管线字段。
 */
@Data
public class RawLogEvent {

    private String eventId;
    private String sourceId;
    private String sourceType;
    private String host;
    private Long offset;
    private Long ingestTime;
    private Long eventTime;
    private String level;
    private String content;
}
