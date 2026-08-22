package com.award.log.util;

/**
 * 链路追踪 ID 生成器。
 */
public interface TraceIdGenerator {

    String nextId();
}
