package com.award.log.util;

import org.springframework.stereotype.Component;

/**
 * {@link TimeSource} 的系统时钟实现，返回 {@code System.currentTimeMillis()}。
 */
@Component
public class SystemTimeSource implements TimeSource {

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
