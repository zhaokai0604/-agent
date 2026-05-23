package com.award.log.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 运行环境探测（Agent / MCP 在 Windows 与 Linux/麒麟 下分支用）。
 * <p>赛题交付目标：银河麒麟高级服务器版 V11 + LoongArch64。</p>
 */
public final class OsRuntime {

    private static final String OS_NAME = System.getProperty("os.name", "");
    private static final String OS_VERSION = System.getProperty("os.version", "");
    private static final String OS_ARCH = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    private static final String OS_NAME_LOWER = OS_NAME.toLowerCase(Locale.ROOT);

    private static final boolean WINDOWS = OS_NAME_LOWER.contains("win");
    private static final boolean KYLIN = OS_NAME_LOWER.contains("kylin")
            || readFileContains("/etc/.kyinfo", "kylin")
            || readFileContains("/etc/os-release", "kylin");
    private static final boolean LOONGARCH = OS_ARCH.contains("loongarch")
            || OS_ARCH.equals("mips64")
            || OS_ARCH.contains("loongson");

    private OsRuntime() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    public static boolean isUnixLike() {
        return !WINDOWS;
    }

    /** 银河麒麟（含高级服务器版 V11 等） */
    public static boolean isKylin() {
        return !WINDOWS && KYLIN;
    }

    /** 龙芯 LoongArch64（赛题龙架构） */
    public static boolean isLoongArch() {
        return LOONGARCH;
    }

    public static String osName() {
        return OS_NAME;
    }

    public static String osVersion() {
        return OS_VERSION;
    }

    public static String osArch() {
        return System.getProperty("os.arch", "");
    }

    /**
     * 供启动日志、验收 API、审计上下文使用。
     */
    public static Map<String, Object> platformSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("osName", OS_NAME);
        m.put("osVersion", OS_VERSION);
        m.put("osArch", osArch());
        m.put("windows", WINDOWS);
        m.put("unixLike", isUnixLike());
        m.put("kylin", isKylin());
        m.put("loongArch", isLoongArch());
        m.put("deliveryTarget", "Kylin-V11-LoongArch64");
        m.put("deliveryTargetMatch", isKylin() && isLoongArch());
        m.put("javaVersion", System.getProperty("java.version", ""));
        m.put("javaVendor", System.getProperty("java.vendor", ""));
        return m;
    }

    private static boolean readFileContains(String path, String needle) {
        if (WINDOWS) {
            return false;
        }
        try {
            String content = java.nio.file.Files.readString(java.nio.file.Path.of(path))
                    .toLowerCase(Locale.ROOT);
            return content.contains(needle.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }
}
