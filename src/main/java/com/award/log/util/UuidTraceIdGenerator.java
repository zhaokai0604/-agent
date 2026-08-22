package com.award.log.util;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 基于 UUID 生成追踪 ID。
 */
@Component
public class UuidTraceIdGenerator implements TraceIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
