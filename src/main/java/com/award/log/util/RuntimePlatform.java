package com.award.log.util;

/**
 * 运行时平台抽象：Windows/Unix 判断及默认日志/临时目录。
 */
public interface RuntimePlatform {

    boolean isWindows();

    default String defaultLogRoot() {
        return isWindows() ? "C:\\Windows\\Logs" : "/var/log";
    }

    default String defaultTempRoot() {
        return isWindows() ? "C:\\Windows\\Temp" : "/tmp";
    }
}
