package com.award.log.util;

/**
 * 运行环境探测（Agent / MCP 在 Windows 与 Linux 下分支用）。
 */
public final class OsRuntime {

    private static final boolean WINDOWS = isWindows0();

    private OsRuntime() {
    }

    private static boolean isWindows0() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    public static boolean isUnixLike() {
        return !WINDOWS;
    }
}
