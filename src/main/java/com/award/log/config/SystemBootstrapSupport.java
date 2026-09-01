package com.award.log.config;

import com.award.log.util.OsRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Startup-time platform detection and config reconciliation for platform-derived defaults.
 */
public final class SystemBootstrapSupport {

    private static final String STATUS_KEY = "bootstrap";

    private SystemBootstrapSupport() {
    }

    public static Map<String, Object> reconcileForCurrentPlatform(String source) {
        boolean windows = OsRuntime.isWindows();
        String now = Instant.now().toString();
        String currentPlatform = windows ? "windows" : "linux";

        Map<String, Object> overrideRoot = new LinkedHashMap<>(
                SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.overrideFile()));
        Map<String, Object> collector = new LinkedHashMap<>(
                SystemConfigFileSupport.nestedMap(overrideRoot.get("collector")));
        Map<String, Object> runtime = new LinkedHashMap<>(
                SystemConfigFileSupport.nestedMap(overrideRoot.get("runtime")));
        Map<String, Object> compatPathPolicy = new LinkedHashMap<>(
                SystemConfigFileSupport.nestedMap(overrideRoot.get("pathPolicy")));
        Map<String, Object> pathPolicyFileRoot = new LinkedHashMap<>(
                SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.pathPolicyFile()));

        List<String> changedKeys = new ArrayList<>();
        List<String> generatedKeys = new ArrayList<>();

        reconcileText(
                collector,
                "fileRoot",
                defaultCollectorRoot(windows),
                value -> value == null || value.isBlank() || looksMismatchedForPlatform(value, windows),
                "collector.fileRoot",
                changedKeys,
                generatedKeys);

        reconcileList(
                runtime,
                "patrolInspectRoots",
                defaultInspectRoots(windows),
                value -> value.isEmpty() || listLooksMismatchedForPlatform(value, windows),
                "runtime.patrolInspectRoots",
                changedKeys,
                generatedKeys);

        reconcileList(
                compatPathPolicy,
                "readPrefixes",
                defaultReadPrefixes(windows),
                value -> value.isEmpty() || listLooksMismatchedForPlatform(value, windows),
                "pathPolicy.readPrefixes",
                changedKeys,
                generatedKeys);

        reconcileList(
                compatPathPolicy,
                "cleanRoots",
                defaultCleanRoots(windows),
                value -> value.isEmpty() || listLooksMismatchedForPlatform(value, windows),
                "pathPolicy.cleanRoots",
                changedKeys,
                generatedKeys);

        reconcileList(
                compatPathPolicy,
                "logCleanupRoots",
                defaultLogCleanupRoots(windows),
                value -> value.isEmpty() || listLooksMismatchedForPlatform(value, windows),
                "pathPolicy.logCleanupRoots",
                changedKeys,
                generatedKeys);

        reconcileList(
                pathPolicyFileRoot,
                "readPrefixes",
                compatPathPolicy.containsKey("readPrefixes")
                        ? SystemConfigFileSupport.stringList(compatPathPolicy.get("readPrefixes"))
                        : defaultReadPrefixes(windows),
                value -> value.isEmpty() || listLooksMismatchedForPlatform(value, windows),
                "pathPolicyFile.readPrefixes",
                changedKeys,
                generatedKeys);

        reconcileList(
                pathPolicyFileRoot,
                "cleanRoots",
                compatPathPolicy.containsKey("cleanRoots")
                        ? SystemConfigFileSupport.stringList(compatPathPolicy.get("cleanRoots"))
                        : defaultCleanRoots(windows),
                value -> value.isEmpty() || listLooksMismatchedForPlatform(value, windows),
                "pathPolicyFile.cleanRoots",
                changedKeys,
                generatedKeys);

        reconcileList(
                pathPolicyFileRoot,
                "logCleanupRoots",
                compatPathPolicy.containsKey("logCleanupRoots")
                        ? SystemConfigFileSupport.stringList(compatPathPolicy.get("logCleanupRoots"))
                        : defaultLogCleanupRoots(windows),
                value -> value.isEmpty() || listLooksMismatchedForPlatform(value, windows),
                "pathPolicyFile.logCleanupRoots",
                changedKeys,
                generatedKeys);

        overrideRoot.put("collector", collector);
        overrideRoot.put("runtime", runtime);
        overrideRoot.put("pathPolicy", compatPathPolicy);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", source == null || source.isBlank() ? "startup-auto-reconcile" : source.trim());
        status.put("lastRunAt", now);
        status.put("platform", buildPlatformSummary());
        status.put("platformKey", currentPlatform);
        status.put("platformFingerprint", currentPlatform + ":" + System.getProperty("os.arch", ""));
        status.put("corrected", !changedKeys.isEmpty());
        status.put("changedKeys", changedKeys);
        status.put("autoGeneratedKeys", generatedKeys);
        status.put("collectorCandidateRoots", candidateCollectorRoots(windows));
        status.put("whitelistScan", scanWhitelistCandidates(windows));
        status.put("configDir", SystemConfigFileSupport.activeConfigDir());
        status.put("capabilities", buildCapabilitySnapshot());
        overrideRoot.put(STATUS_KEY, status);

        pathPolicyFileRoot.put("platform", currentPlatform);
        pathPolicyFileRoot.put("savedAt", now);
        pathPolicyFileRoot.put(STATUS_KEY, Map.of(
                "lastRunAt", now,
                "platformKey", currentPlatform,
                "changed", !changedKeys.isEmpty()));

        SystemConfigFileSupport.writeJsonFile(SystemConfigFileSupport.overrideFile(), overrideRoot);
        SystemConfigFileSupport.writeJsonFile(SystemConfigFileSupport.pathPolicyFile(), pathPolicyFileRoot);
        return status;
    }

    public static Map<String, Object> readBootstrapStatus() {
        Map<String, Object> overrideRoot = SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.overrideFile());
        Map<String, Object> status = new LinkedHashMap<>(SystemConfigFileSupport.nestedMap(overrideRoot.get(STATUS_KEY)));
        if (status.isEmpty()) {
            status.put("platform", buildPlatformSummary());
            status.put("platformKey", OsRuntime.isWindows() ? "windows" : "linux");
            status.put("corrected", false);
            status.put("changedKeys", List.of());
            status.put("autoGeneratedKeys", List.of());
            status.put("collectorCandidateRoots", candidateCollectorRoots(OsRuntime.isWindows()));
            status.put("whitelistScan", scanWhitelistCandidates(OsRuntime.isWindows()));
            status.put("configDir", SystemConfigFileSupport.activeConfigDir());
            status.put("capabilities", buildCapabilitySnapshot());
        }
        status.put("overrideFileExists", Files.exists(SystemConfigFileSupport.overrideFile()));
        status.put("pathPolicyFileExists", Files.exists(SystemConfigFileSupport.pathPolicyFile()));
        return status;
    }

    public static Map<String, Object> buildCapabilitySnapshot() {
        LinkedHashMap<String, Object> capabilities = new LinkedHashMap<>();
        boolean windows = OsRuntime.isWindows();
        capabilities.put("windows", windows);
        capabilities.put("linux", !windows);
        capabilities.put("powershell", windows);
        capabilities.put("systemctl", !windows && hasCommand("systemctl"));
        capabilities.put("journalctl", !windows && hasCommand("journalctl"));
        capabilities.put("docker", hasCommand(windows ? "docker.exe" : "docker"));
        capabilities.put("schtasks", windows && hasCommand("schtasks.exe"));
        capabilities.put("netsh", windows && hasCommand("netsh.exe"));
        capabilities.put("getService", windows);
        return capabilities;
    }

    private static Map<String, Object> buildPlatformSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("osName", System.getProperty("os.name", ""));
        summary.put("osArch", System.getProperty("os.arch", ""));
        summary.put("windows", OsRuntime.isWindows());
        summary.put("kylin", OsRuntime.isKylin());
        summary.put("loongArch", OsRuntime.isLoongArch());
        return summary;
    }

    private static List<String> candidateCollectorRoots(boolean windows) {
        if (windows) {
            return List.of("logs", "C:/Windows/Logs", "C:/inetpub/logs");
        }
        return List.of("/var/log", "/var/log/journal", "/tmp");
    }

    private static String defaultCollectorRoot(boolean windows) {
        return windows ? "logs" : "/var/log";
    }

    private static List<String> defaultInspectRoots(boolean windows) {
        if (windows) {
            return List.of(
                    "logs",
                    "C:/Windows/Logs",
                    "C:/Windows/System32/winevt/Logs",
                    "C:/Users/Administrator/AppData/Local/Temp",
                    "C:/Windows/Temp",
                    "C:/Temp",
                    "D:/Temp",
                    "E:/Temp",
                    "F:/Temp");
        }
        return List.of("/var/log", "/var/log/journal");
    }

    private static List<String> defaultReadPrefixes(boolean windows) {
        if (windows) {
            return List.of(
                    "logs",
                    "C:/Windows/Logs",
                    "C:/Windows/System32/winevt/Logs",
                    "C:/inetpub/logs",
                    "C:/ProgramData/logs",
                    "C:/ProgramData/Microsoft/Windows/WER/ReportArchive",
                    "C:/ProgramData/Microsoft/Windows/WER/ReportQueue",
                    "C:/Users/Administrator/AppData/Local/Temp",
                    "C:/Windows/Temp",
                    "C:/Temp",
                    "D:/Temp",
                    "E:/Temp",
                    "F:/Temp");
        }
        return List.of(
                "/var/log",
                "/var/log/journal",
                "/tmp",
                "/var/tmp",
                "/var/cache");
    }

    private static List<String> defaultCleanRoots(boolean windows) {
        if (windows) {
            return List.of(
                    "C:/Users/Administrator/AppData/Local/Temp",
                    "C:/Windows/Temp",
                    "C:/Temp",
                    "D:/Temp",
                    "E:/Temp",
                    "F:/Temp");
        }
        return List.of("/tmp", "/var/tmp", "/var/cache");
    }

    private static List<String> defaultLogCleanupRoots(boolean windows) {
        if (windows) {
            return List.of(
                    "logs",
                    "C:/Windows/Logs",
                    "C:/Windows/System32/winevt/Logs",
                    "C:/inetpub/logs",
                    "C:/ProgramData/logs");
        }
        return List.of("/var/log", "/var/log/journal");
    }

    /**
     * 启动时真实探测本机可用白名单候选。结果只写入状态快照；策略本身仍受敏感路径硬拒绝和
     * AgentPathPolicy 的校验，避免把扫描到的任意目录直接变为可写权限。
     */
    private static Map<String, Object> scanWhitelistCandidates(boolean windows) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        candidates.addAll(defaultReadPrefixes(windows));
        candidates.addAll(defaultCleanRoots(windows));
        candidates.addAll(defaultLogCleanupRoots(windows));
        for (String raw : dedupe(candidates)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", raw);
            if (isSensitiveAutoWritePath(raw, windows)) {
                row.put("accepted", false);
                row.put("reason", "敏感目录，不自动授予写入白名单");
                entries.add(row);
                continue;
            }
            try {
                Path path = Path.of(raw).toAbsolutePath().normalize();
                boolean exists = Files.exists(path);
                row.put("normalizedPath", path.toString());
                row.put("exists", exists);
                row.put("readable", exists && Files.isReadable(path));
                row.put("writable", exists && Files.isWritable(path));
                row.put("accepted", exists && Files.isReadable(path));
                row.put("reason", exists ? "已发现并纳入当前平台扫描结果" : "目录当前不存在，保留为平台默认候选");
            } catch (Exception e) {
                row.put("accepted", false);
                row.put("reason", "路径无法规范化：" + e.getClass().getSimpleName());
            }
            entries.add(row);
        }
        long accepted = entries.stream().filter(e -> Boolean.TRUE.equals(e.get("accepted"))).count();
        result.put("platform", windows ? "windows" : (OsRuntime.isKylin() ? "kylin" : "linux"));
        result.put("scannedAt", Instant.now().toString());
        result.put("acceptedCount", accepted);
        result.put("skippedCount", entries.size() - accepted);
        result.put("entries", entries);
        return result;
    }

    private static boolean isSensitiveAutoWritePath(String raw, boolean windows) {
        String value = raw == null ? "" : raw.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (windows) {
            return value.matches("^[a-z]:/?$") || value.startsWith("c:/windows/system32")
                    || value.contains("/appdata/roaming") || value.contains("/programdata/microsoft/credentials");
        }
        return "/".equals(value) || value.startsWith("/etc") || value.startsWith("/home")
                || value.startsWith("/root") || value.startsWith("/usr") || value.startsWith("/boot");
    }

    private static void reconcileText(Map<String, Object> root,
                                      String key,
                                      String defaultValue,
                                      java.util.function.Predicate<String> shouldReplace,
                                      String changedKey,
                                      List<String> changedKeys,
                                      List<String> generatedKeys) {
        String current = root.get(key) == null ? "" : String.valueOf(root.get(key)).trim();
        if (!shouldReplace.test(current)) {
            return;
        }
        if (!Objects.equals(current, defaultValue)) {
            changedKeys.add(changedKey);
        }
        if (current.isBlank()) {
            generatedKeys.add(changedKey);
        }
        root.put(key, defaultValue);
    }

    private static void reconcileList(Map<String, Object> root,
                                      String key,
                                      List<String> defaults,
                                      java.util.function.Predicate<List<String>> shouldReplace,
                                      String changedKey,
                                      List<String> changedKeys,
                                      List<String> generatedKeys) {
        List<String> current = dedupe(SystemConfigFileSupport.stringList(root.get(key)));
        if (!shouldReplace.test(current)) {
            return;
        }
        if (!Objects.equals(current, defaults)) {
            changedKeys.add(changedKey);
        }
        if (current.isEmpty()) {
            generatedKeys.add(changedKey);
        }
        root.put(key, defaults);
    }

    private static List<String> dedupe(List<String> values) {
        Set<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                set.add(value.trim());
            }
        }
        return List.copyOf(set);
    }

    private static boolean listLooksMismatchedForPlatform(List<String> values, boolean windows) {
        if (values == null || values.isEmpty()) {
            return true;
        }
        for (String value : values) {
            if (looksMismatchedForPlatform(value, windows)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksMismatchedForPlatform(String value, boolean windows) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String trimmed = value.trim();
        if (windows) {
            if ("logs".equalsIgnoreCase(trimmed)) {
                return false;
            }
            return trimmed.startsWith("/")
                    || trimmed.startsWith("/var/")
                    || trimmed.startsWith("/etc/")
                    || trimmed.startsWith("/tmp");
        }
        return trimmed.matches("(?i)^[A-Z]:[/\\\\].*")
                || trimmed.startsWith("\\\\");
    }

    private static boolean hasCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        String[] parts = path.split(java.io.File.pathSeparator);
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            Path candidate = Path.of(part.trim(), command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
            if (OsRuntime.isWindows() && !command.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                Path exeCandidate = Path.of(part.trim(), command + ".exe");
                if (Files.isRegularFile(exeCandidate) && Files.isExecutable(exeCandidate)) {
                    return true;
                }
            }
        }
        return false;
    }
}
