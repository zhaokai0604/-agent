package com.award.log.security;

import com.award.log.util.OsRuntime;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 运维工具路径白名单 / 黑名单（国赛「确定性」：禁止任意路径 find/rm/tail）。
 * Windows 下使用 {@code agent.paths.windows-*} 前缀；Linux 使用 {@code agent.paths.read-prefixes} 等。
 */
@Slf4j
@Component
public class OpsPathPolicy {

    @Getter
    @Value("${agent.paths.policy-version:path-policy-v1}")
    private String policyVersion;

    @Value("${agent.paths.read-prefixes:/var/log,/tmp}")
    private String readPrefixesRaw;

    @Value("${agent.paths.clean-roots:/tmp,/var/tmp}")
    private String cleanRootsRaw;

    @Value("${agent.paths.config-prefixes:/etc/nginx/,/etc/ssh/,/etc/default/}")
    private String configPrefixesRaw;

    @Value("${agent.paths.denied-substrings:/etc/shadow,/etc/gshadow,/.ssh,/root/,/boot/}")
    private String deniedRaw;

    /**
     * 权限探测允许的文件/解析后命令路径前缀（配合 PrivilegeTool，避免任意路径 test）。
     */
    @Value("${agent.paths.privilege-prefixes:/usr/bin,/usr/sbin,/bin,/sbin,/usr/local/bin,/usr/lib}")
    private String privilegePrefixesRaw;

    /** Windows：允许读取日志/诊断文本的路径前缀（盘符路径用 / 或 \\ 均可）。 */
    @Value("${agent.paths.windows-read-prefixes:C:/Windows/Logs,C:/ProgramData,C:/Windows/System32/winevt/Logs,C:/inetpub/logs}")
    private String windowsReadPrefixesRaw;

    /** Windows：允许临时清理的根目录。 */
    @Value("${agent.paths.windows-clean-roots:C:/Windows/Temp,C:/Temp}")
    private String windowsCleanRootsRaw;

    /** Windows：允许做配置/存在性检查的路径前缀。 */
    @Value("${agent.paths.windows-config-prefixes:C:/Windows/System32/drivers/etc,C:/ProgramData,C:/Windows/System32/winevt/Logs}")
    private String windowsConfigPrefixesRaw;

    /** Windows：允许 privilege 探测的系统目录（cmd、驱动等）。 */
    @Value("${agent.paths.windows-privilege-prefixes:C:/Windows/System32,C:/Windows/SysWOW64,C:/Program Files,C:/Program Files (x86)}")
    private String windowsPrivilegePrefixesRaw;

    /** 与日志采集根目录对齐，避免「生产盘符」上的日志被策略误拦（见 {@code log.collector.file.path}）。 */
    @Value("${log.collector.file.path:}")
    private String logCollectorFilePath;

    private List<String> readPrefixes = List.of();
    private List<String> cleanRoots = List.of();
    private List<String> configPrefixes = List.of();
    private List<String> deniedSubstrings = List.of();
    private List<String> privilegePrefixes = List.of();

    private List<String> windowsReadPrefixes = List.of();
    private List<String> windowsCleanRoots = List.of();
    private List<String> windowsConfigPrefixes = List.of();
    private List<String> windowsPrivilegePrefixes = List.of();

    @PostConstruct
    void init() {
        readPrefixes = split(readPrefixesRaw);
        if (!OsRuntime.isWindows() && logCollectorFilePath != null && !logCollectorFilePath.isBlank()) {
            String norm = normalizeUnixPath(logCollectorFilePath.trim());
            if (!norm.isEmpty()) {
                List<String> merged = new ArrayList<>(readPrefixes);
                boolean exists = false;
                for (String e : merged) {
                    if (normalizeUnixPath(e).equals(norm)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    merged.add(norm);
                }
                readPrefixes = merged;
            }
        }
        cleanRoots = split(cleanRootsRaw);
        configPrefixes = split(configPrefixesRaw);
        deniedSubstrings = split(deniedRaw);
        privilegePrefixes = split(privilegePrefixesRaw);
        windowsReadPrefixes = new ArrayList<>(split(windowsReadPrefixesRaw));
        windowsCleanRoots = new ArrayList<>(split(windowsCleanRootsRaw));
        windowsConfigPrefixes = new ArrayList<>(split(windowsConfigPrefixesRaw));
        windowsPrivilegePrefixes = new ArrayList<>(split(windowsPrivilegePrefixesRaw));
        if (OsRuntime.isWindows()) {
            String tmp = normalizeWindowsPath(System.getProperty("java.io.tmpdir", ""));
            if (!tmp.isEmpty()) {
                addIfAbsent(windowsReadPrefixes, tmp);
                addIfAbsent(windowsCleanRoots, tmp);
            }
            if (logCollectorFilePath != null && !logCollectorFilePath.isBlank()) {
                addIfAbsent(windowsReadPrefixes, logCollectorFilePath.trim());
            }
        }
        log.info("OpsPathPolicy [{}] read={} clean={} config={} privilege={} winRead={} winClean={}",
                policyVersion, readPrefixes, cleanRoots, configPrefixes, privilegePrefixes,
                windowsReadPrefixes, windowsCleanRoots);
    }

    private static void addIfAbsent(List<String> list, String path) {
        String n = normalizeWindowsPath(path);
        if (n.isEmpty()) {
            return;
        }
        for (String e : list) {
            if (normalizeWindowsPath(e).equalsIgnoreCase(n)) {
                return;
            }
        }
        list.add(path.trim());
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(normalizeDirPrefix(t));
            }
        }
        return out;
    }

    private static String normalizeDirPrefix(String p) {
        String t = p.trim();
        if (t.endsWith("/") && t.length() > 1) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.length() > 2 && t.charAt(1) == ':' && (t.endsWith("\\") || t.endsWith("/"))) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** Windows 盘符路径规范化：统一为 {@code D:/path} 形式（大写盘符）。 */
    public static String normalizeWindowsPath(String path) {
        if (path == null) {
            return "";
        }
        String t = path.trim().replace('\\', '/');
        if (t.isEmpty()) {
            return "";
        }
        if (t.startsWith("//")) {
            return t;
        }
        if (t.length() >= 2 && t.charAt(1) == ':' && Character.isLetter(t.charAt(0))) {
            char d = Character.toUpperCase(t.charAt(0));
            t = d + t.substring(1);
        }
        while (t.length() > 3 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static boolean startsWithWindowsPrefix(String normalizedPath, String prefix) {
        String px = normalizeWindowsPath(prefix);
        if (px.isEmpty() || normalizedPath.isEmpty()) {
            return false;
        }
        return normalizedPath.equalsIgnoreCase(px)
                || normalizedPath.toLowerCase().startsWith(px.toLowerCase() + "/");
    }

    /** 规范化为绝对路径样式（Linux）。 */
    public String normalizeUnixPath(String path) {
        if (path == null) {
            return "";
        }
        String t = path.trim().replace('\\', '/');
        if (t.isEmpty()) {
            return "";
        }
        if (!t.startsWith("/")) {
            t = "/" + t;
        }
        while (t.length() > 1 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    public boolean hasTraversalOrNullBytes(String path) {
        if (path == null) {
            return true;
        }
        return path.contains("..") || path.contains("\0");
    }

    public boolean matchesDenied(String path) {
        if (path == null) {
            return true;
        }
        String lower = path.toLowerCase();
        for (String d : deniedSubstrings) {
            if (d != null && !d.isBlank() && lower.contains(d.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 日志读取类路径（analyzeLogs / tail）。 */
    public boolean isAllowedLogReadPath(String path) {
        if (hasTraversalOrNullBytes(path) || matchesDenied(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String wn = normalizeWindowsPath(path);
            if (wn.isEmpty() || wn.startsWith("//")) {
                return false;
            }
            for (String prefix : windowsReadPrefixes) {
                if (startsWithWindowsPrefix(wn, prefix)) {
                    return true;
                }
            }
            return false;
        }
        String norm = normalizeUnixPath(path);
        if (norm.isEmpty()) {
            return false;
        }
        for (String prefix : readPrefixes) {
            String px = normalizeUnixPath(prefix);
            if (norm.equals(px) || norm.startsWith(px + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 临时清理 find/rm 的根目录。 */
    public boolean isAllowedCleanDirectory(String path) {
        if (hasTraversalOrNullBytes(path) || matchesDenied(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String wn = normalizeWindowsPath(path);
            if (wn.isEmpty() || wn.startsWith("//")) {
                return false;
            }
            for (String root : windowsCleanRoots) {
                if (startsWithWindowsPrefix(wn, root)) {
                    return true;
                }
            }
            return false;
        }
        String norm = normalizeUnixPath(path);
        if (norm.isEmpty()) {
            return false;
        }
        for (String root : cleanRoots) {
            String rt = normalizeUnixPath(root);
            if (norm.equals(rt) || norm.startsWith(rt + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 配置检查（只读语法校验）允许的路径前缀。 */
    public boolean isAllowedConfigCheckPath(String path) {
        if (hasTraversalOrNullBytes(path) || matchesDenied(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String wn = normalizeWindowsPath(path);
            if (wn.isEmpty() || wn.startsWith("//")) {
                return false;
            }
            for (String prefix : windowsConfigPrefixes) {
                if (startsWithWindowsPrefix(wn, prefix)) {
                    return true;
                }
            }
            return false;
        }
        String norm = normalizeUnixPath(path);
        if (norm.isEmpty()) {
            return false;
        }
        for (String prefix : configPrefixes) {
            String px = normalizeUnixPath(prefix);
            if (norm.equals(px) || norm.startsWith(px)) {
                return true;
            }
        }
        return false;
    }

    /** 磁盘热点 du 扫描允许的根路径（读日志区 + 临时目录白名单）。 */
    public boolean isAllowedDiskInsightRoot(String path) {
        return isAllowedLogReadPath(path) || isAllowedCleanDirectory(path);
    }

    /** PrivilegeTool：允许探测读权限的路径（日志/临时/配置区 + 系统二进制前缀）。 */
    public boolean isAllowedPrivilegeProbePath(String path) {
        if (hasTraversalOrNullBytes(path) || matchesDenied(path)) {
            return false;
        }
        if (OsRuntime.isWindows()) {
            String wn = normalizeWindowsPath(path);
            if (wn.isEmpty() || wn.startsWith("//")) {
                return false;
            }
            if (isAllowedLogReadPath(path) || isAllowedCleanDirectory(path) || isAllowedConfigCheckPath(path)) {
                return true;
            }
            for (String prefix : windowsPrivilegePrefixes) {
                if (startsWithWindowsPrefix(wn, prefix)) {
                    return true;
                }
            }
            return false;
        }
        String norm = normalizeUnixPath(path);
        if (norm.isEmpty()) {
            return false;
        }
        if (isAllowedLogReadPath(path) || isAllowedCleanDirectory(path) || isAllowedConfigCheckPath(path)) {
            return true;
        }
        for (String prefix : privilegePrefixes) {
            String px = normalizeUnixPath(prefix);
            if (norm.equals(px) || norm.startsWith(px + "/")) {
                return true;
            }
        }
        return false;
    }

    public String rejectReason(String category) {
        return "路径策略拦截[" + policyVersion + "]: " + category + "，请联系管理员调整 agent.paths.* 配置";
    }
}
