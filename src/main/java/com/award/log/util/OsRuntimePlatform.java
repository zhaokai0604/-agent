package com.award.log.util;

import org.springframework.stereotype.Component;

/**
 * {@link RuntimePlatform} 的 Spring 实现，委托 {@link OsRuntime} 判断是否 Windows。
 */
@Component
public class OsRuntimePlatform implements RuntimePlatform {

    @Override
    public boolean isWindows() {
        return OsRuntime.isWindows();
    }
}
