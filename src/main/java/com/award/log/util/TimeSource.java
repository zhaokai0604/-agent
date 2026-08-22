package com.award.log.util;

/**
 * 可注入时钟，便于测试替换当前时间。
 */
public interface TimeSource {

    long currentTimeMillis();
}
