package com.award.log.security;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 运维工具路径白名单 / 黑名单（国赛「确定性」：禁止任意路径 find/rm/tail）。
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

    private List<String> readPrefixes = List.of();
    private List<String> cleanRoots = List.of();
    private List<String> configPrefixes = List.of();
    private List<String> deniedSubstrings = List.of();
    private List<String> privilegePrefixes = List.of();

    @PostConstruct
    void init() {
        readPrefixes = split(readPrefixesRaw);
        cleanRoots = split(cleanRootsRaw);
        configPrefixes = split(configPrefixesRaw);
        deniedSubstrings = split(deniedRaw);
        privilegePrefixes = split(privilegePrefixesRaw);
        log.info("OpsPathPolicy [{}] read={} clean={} config={} privilege={}",
                policyVersion, readPrefixes, cleanRoots, configPrefixes, privilegePrefixes);
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
        return t;
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
