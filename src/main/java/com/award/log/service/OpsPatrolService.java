package com.award.log.service;

import com.award.log.agent.AutonomousOpsOrchestrator;
import com.award.log.agent.DrainTemplateNoveltyTracker;
import com.award.log.model.patrol.PatrolCorrelationSnapshot;
import com.award.log.model.patrol.PatrolFinding;
import com.award.log.security.McpToolSurface;
import com.award.log.handler.PerformanceWebSocketHandler;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 主动巡检：多维关联、磁盘一级子目录热点、告警级别分布、异常日志基线、僵尸进程、Drain 新模板；
 * 支持去重冷却、WebSocket 推送 enriched envelope。
 */
@Slf4j
@Service
public class OpsPatrolService {

    private static final int MAX_ALERTS = 120;

    private final StatisticsService statisticsService;
    private final AiLogAlarmService aiLogAlarmService;
    private final DrainTemplateNoveltyTracker noveltyTracker;
    private final OpsPathPolicy opsPathPolicy;

    @Autowired(required = false)
    private OpsAutoRemediationService opsAutoRemediationService;

    @Autowired(required = false)
    private AutonomousOpsOrchestrator autonomousOpsOrchestrator;

    @Autowired(required = false)
    private PerformanceWebSocketHandler performanceWebSocketHandler;

    @Autowired(required = false)
    private PatrolHistoryService patrolHistoryService;

    @Value("${agent.autonomous.run-after-patrol:false}")
    private boolean autonomousRunAfterPatrol;

    @Value("${ops.patrol.disk-warn-percent:80}")
    private double diskWarnPercent;

    @Value("${ops.patrol.cpu-warn-percent:85}")
    private double cpuWarnPercent;

    @Value("${ops.patrol.anomaly-spike-factor:2.0}")
    private double anomalySpikeFactor;

    @Value("${ops.patrol.inspect-roots:/var/log,/tmp}")
    private String inspectRootsRaw;

    @Value("${ops.patrol.dedup-window-ms:900000}")
    private long dedupWindowMs;

    @Value("${ops.patrol.disk-hotspot-top-n:5}")
    private int diskHotspotTopN;

    @Value("${ops.patrol.error-alarm-min:3}")
    private int errorAlarmMin;

    /** 热点扫描递归深度（与 du --max-depth 对齐） */
    @Value("${ops.patrol.disk-hotspot-max-depth:3}")
    private int diskHotspotMaxDepth;

    /** 纯 Java 递归扫描时的最大访问节点数，防止超大目录拖死巡检 */
    @Value("${ops.patrol.disk-hotspot-visit-budget:12000}")
    private int diskHotspotVisitBudget;

    @Value("${ops.patrol.anomaly-min-delta:5}")
    private int anomalyMinDelta;

    /** 非突增轮次基线 EMA 系数（越大越紧跟当前值） */
    @Value("${ops.patrol.anomaly-baseline-ema-alpha:0.35}")
    private double anomalyBaselineEmaAlpha;

    private final ConcurrentLinkedDeque<Map<String, Object>> recentAlerts = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, Long> dedupSuppressUntil = new ConcurrentHashMap<>();

    private volatile List<Map<String, Object>> lastFindings = List.of();
    private volatile long lastAnomalyTotal = -1L;

    private volatile boolean anomalyBaselineInitialized = false;
    /** 每轮巡检计算的关联快照（与告警条目解耦，供轻量 API / 顶栏展示） */
    private volatile Map<String, Object> lastCorrelationSnapshot = Map.of();

    public OpsPatrolService(
            StatisticsService statisticsService,
            AiLogAlarmService aiLogAlarmService,
            DrainTemplateNoveltyTracker noveltyTracker,
            OpsPathPolicy opsPathPolicy) {
        this.statisticsService = statisticsService;
        this.aiLogAlarmService = aiLogAlarmService;
        this.noveltyTracker = noveltyTracker;
        this.opsPathPolicy = opsPathPolicy;
    }

    public void applyHotConfig(List<String> inspectRoots,
                               double diskWarnPercent,
                               double cpuWarnPercent,
                               double anomalySpikeFactor,
                               int errorAlarmMin) {
        if (inspectRoots != null && !inspectRoots.isEmpty()) {
            this.inspectRootsRaw = String.join(",", inspectRoots);
        }
        this.diskWarnPercent = diskWarnPercent;
        this.cpuWarnPercent = cpuWarnPercent;
        this.anomalySpikeFactor = anomalySpikeFactor;
        this.errorAlarmMin = errorAlarmMin;
    }

    public List<Map<String, Object>> getRecentAlerts(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> a : recentAlerts) {
            out.add(a);
            if (++i >= limit) {
                break;
            }
        }
        return out;
    }

    public List<Map<String, Object>> getLastFindingsSnapshot() {
        return lastFindings;
    }

    public Map<String, Object> getLastCorrelationSnapshot() {
        return lastCorrelationSnapshot;
    }

    public void runPatrolCycle() {
        long nowMs = System.currentTimeMillis();
        List<Map<String, Object>> findings = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        String ts = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> perf = safePerf();
        double disk = asDouble(perf.get("diskUsage"));
        double cpu = asDouble(perf.get("cpuUsage"));
        double mem = asDouble(perf.get("memoryUsage"));

        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("timestamp", ts);
        correlation.put("diskUsagePct", disk);
        correlation.put("cpuUsagePct", cpu);
        correlation.put("memoryUsagePct", mem);

        long errorAlarms = 0;
        long fatalAlarms = 0;
        long totalAlarms = 0;
        try {
            Map<String, Object> alarmStats = aiLogAlarmService.getAlarmStatistics(1, null, null);
            if (alarmStats != null) {
                totalAlarms = asLong(alarmStats.get("totalAlarms"));
                Object dist = alarmStats.get("levelDistribution");
                if (dist instanceof List<?> list) {
                    for (Object row : list) {
                        if (!(row instanceof Map)) {
                            continue;
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) row;
                        String lvl = String.valueOf(m.getOrDefault("level", "")).toUpperCase(Locale.ROOT);
                        long c = asLong(m.get("count"));
                        if (lvl.contains("ERROR")) {
                            errorAlarms += c;
                        }
                        if (lvl.contains("FATAL")) {
                            fatalAlarms += c;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("巡检：告警统计不可用 {}", e.getMessage());
        }
        correlation.put("alarmTotal24h", totalAlarms);
        correlation.put("alarmErrorApprox", errorAlarms);
        correlation.put("alarmFatalApprox", fatalAlarms);

        long anomalyTotal = -1;
        try {
            Map<String, Object> an = statisticsService.getAnomalyLogStatistics(1);
            if (an != null && an.get("totalAnomalyLogs") instanceof Number n) {
                anomalyTotal = n.longValue();
            }
        } catch (Exception e) {
            log.debug("巡检：异常日志统计不可用 {}", e.getMessage());
        }
        correlation.put("anomalyLogsDay1", anomalyTotal);

        int zombies = countZombieProcesses();
        correlation.put("zombieProcesses", zombies);

        long novelKinds = noveltyTracker.countFirstSeenInWindow(3_600_000L);
        correlation.put("novelDrainTemplateKinds1h", novelKinds);

        List<Map<String, Object>> diskHotspots = scanDiskHotspots();
        correlation.put("diskHotspotsTop", diskHotspots);

        lastCorrelationSnapshot = new LinkedHashMap<>(correlation);

        maybeAddDeduped(finding("WARN", "disk", "磁盘使用率偏高",
                String.format("当前约 %.1f%%，建议排查大文件与日志增长（可用 DiskInsight / LogAnalysis）", disk)),
                findings, nowMs, disk >= diskWarnPercent);

        maybeAddDeduped(finding("WARN", "cpu", "CPU 使用率偏高",
                String.format("当前约 %.1f%%，建议结合进程列表排查热点", cpu)),
                findings, nowMs, cpu >= cpuWarnPercent);

        maybeAddDeduped(finding("WARN", "memory", "内存使用率极高",
                String.format("当前约 %.1f%%，关注 OOM 与页交换", mem)),
                findings, nowMs, mem >= 90);

        if (errorAlarms + fatalAlarms >= errorAlarmMin) {
            maybeAddDeduped(finding("WARN", "alarm_severity", "ERROR/FATAL 级告警较多",
                    String.format("近窗约 ERROR=%d、FATAL=%d（合计告警条目=%d），建议优先处理根因链", errorAlarms, fatalAlarms, totalAlarms)),
                    findings, nowMs, true);
        }

        applyAnomalyBaseline(anomalyTotal, correlation, findings, nowMs);

        if (zombies > 0) {
            maybeAddDeduped(finding("WARN", "process", "检测到僵尸进程",
                    String.format("约 %d 个（Unix Z 状态）；建议检查父进程是否未 wait", zombies)),
                    findings, nowMs, true);
        }

        if (novelKinds >= 3) {
            maybeAddDeduped(finding("INFO", "drain", "近期出现多种新日志模板",
                    String.format("过去约 1 小时首次出现的模板种类约 %d，建议关注是否为新故障模式", novelKinds)),
                    findings, nowMs, true);
        }

        if (!diskHotspots.isEmpty()) {
            Map<String, Object> top = diskHotspots.get(0);
            maybeAddDeduped(finding("INFO", "disk_hotspot", "目录体积累计偏高（递归统计）",
                    String.format("热点示例：%s ≈ %.1f MiB（深度≤%d，详见 correlation.diskHotspotsTop）",
                            top.get("path"), asDouble(top.get("approxMiB")), diskHotspotMaxDepth)),
                    findings, nowMs, asDouble(top.get("approxMiB")) >= 256);
        }

        lastFindings = List.copyOf(findings);

        for (Map<String, Object> f : findings) {
            Map<String, Object> alert = new LinkedHashMap<>(f);
            alert.put("timestamp", ts);
            alert.put("patrolRunTs", ts);
            recentAlerts.addFirst(alert);
            trimAlerts();
        }

        if (!findings.isEmpty() && performanceWebSocketHandler != null) {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("channel", "patrol_alert");
            envelope.put("timestamp", ts);
            envelope.put("findings", findings);
            envelope.put("correlation", correlation);
            try {
                performanceWebSocketHandler.broadcastJson(envelope);
            } catch (Exception e) {
                log.warn("巡检 WebSocket 推送失败: {}", e.getMessage());
            }
        }

        if (!findings.isEmpty()) {
            log.info("[运维巡检] 本轮有效线索 {} 条（已去重冷却）", findings.size());
        }

        if (opsAutoRemediationService != null) {
            try {
                opsAutoRemediationService.afterPatrol(lastCorrelationSnapshot, List.copyOf(findings));
            } catch (Exception e) {
                log.warn("[运维巡检] 自动修复阶段异常: {}", e.getMessage());
            }
        }

        if (autonomousRunAfterPatrol && autonomousOpsOrchestrator != null && !findings.isEmpty()) {
            try {
                autonomousOpsOrchestrator.runScheduled(McpToolSurface.FULL, false);
                log.info("[运维巡检] 已触发全栈自主编排（agent.autonomous.run-after-patrol=true；与 ops.auto-remediation 并行，生产建议仅开其一）");
            } catch (Exception e) {
                log.warn("[运维巡检] 自主运维编排异常: {}", e.getMessage());
            }
        }

        if (patrolHistoryService != null) {
            List<PatrolFinding> typedFindings = findings.stream()
                    .map(PatrolFinding::fromMap)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            patrolHistoryService.saveRun(
                    PatrolCorrelationSnapshot.fromMap(lastCorrelationSnapshot),
                    typedFindings);
        }
    }

    private void maybeAddDeduped(Map<String, Object> finding, List<Map<String, Object>> findings, long nowMs, boolean condition) {
        if (!condition) {
            return;
        }
        String key = String.valueOf(finding.get("level")) + "|"
                + String.valueOf(finding.get("code")) + "|"
                + String.valueOf(finding.get("title"));
        long until = dedupSuppressUntil.getOrDefault(key, 0L);
        if (until > nowMs) {
            return;
        }
        dedupSuppressUntil.put(key, nowMs + dedupWindowMs);
        findings.add(finding);
    }

    private void trimAlerts() {
        while (recentAlerts.size() > MAX_ALERTS) {
            recentAlerts.removeLast();
        }
    }

    private Map<String, Object> safePerf() {
        try {
            Map<String, Object> p = statisticsService.getSystemPerformance(null);
            return p != null ? p : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 磁盘热点：Unix 优先 du 递归统计；Windows 等对子路径做有界深度递归，避免只数一层文件导致误判。
     */
    private List<Map<String, Object>> scanDiskHotspots() {
        List<Map<String, Object>> rows = new ArrayList<>();
        int depth = Math.min(Math.max(diskHotspotMaxDepth, 1), 5);
        for (String raw : inspectRootsRaw.split(",")) {
            String root = raw.trim();
            if (root.isEmpty()) {
                continue;
            }
            String norm = root.replace("\\", "/");
            if (!opsPathPolicy.isAllowedDiskInsightRoot(norm)) {
                continue;
            }
            Path p = Paths.get(OsRuntime.isWindows() ? root : norm);
            try {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                if (OsRuntime.isUnixLike()) {
                    rows.addAll(scanDiskHotspotsViaDu(p, depth));
                } else {
                    rows.addAll(scanDiskHotspotsViaWalk(p, depth));
                }
            } catch (Exception e) {
                log.debug("磁盘热点扫描跳过 {}: {}", root, e.getMessage());
            }
        }
        return rows.stream()
                .sorted(Comparator.comparingDouble(a -> -asDouble(a.get("approxMiB"))))
                .limit(Math.max(1, diskHotspotTopN))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> scanDiskHotspotsViaDu(Path root, int depth) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String rootArg = root.toString().replace('\\', '/');
        try {
            Process process = new ProcessBuilder("du", "-xk", "--max-depth=" + depth, rootArg)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = br.lines().collect(Collectors.joining("\n"));
            }
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("du 热点扫描超时: {}", rootArg);
                return scanDiskHotspotsViaWalk(root, depth);
            }
            if (process.exitValue() != 0) {
                log.debug("du 热点扫描非零退出 {}: {}", process.exitValue(), rootArg);
                return scanDiskHotspotsViaWalk(root, depth);
            }
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int tab = trimmed.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                long kb;
                try {
                    kb = Long.parseLong(trimmed.substring(0, tab).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                String path = trimmed.substring(tab + 1).trim();
                if (path.isEmpty() || kb <= 0) {
                    continue;
                }
                if (path.equals(rootArg)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", path);
                m.put("approxMiB", kb / 1024.0);
                m.put("scanMode", "du");
                rows.add(m);
            }
        } catch (Exception e) {
            log.debug("du 热点扫描失败 {}: {}", rootArg, e.getMessage());
            return scanDiskHotspotsViaWalk(root, depth);
        }
        return rows;
    }

    private List<Map<String, Object>> scanDiskHotspotsViaWalk(Path root, int depth) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            int scanned = 0;
            for (Path child : ds) {
                if (++scanned > 400) {
                    break;
                }
                AtomicInteger visits = new AtomicInteger();
                // 与 du 子目录行一致：统计该子路径下整棵子树（仅用 visitBudget 截断）
                long bytes = recursiveDirectorySizeBytes(child, 64, visits);
                if (bytes <= 0) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", child.toString());
                m.put("approxMiB", bytes / (1024.0 * 1024.0));
                m.put("scanMode", "walk");
                if (visits.get() >= diskHotspotVisitBudget) {
                    m.put("truncated", true);
                }
                rows.add(m);
            }
        } catch (Exception e) {
            log.debug("walk 热点扫描失败 {}: {}", root, e.getMessage());
        }
        return rows;
    }

    private long recursiveDirectorySizeBytes(Path path, int remainingDepth, AtomicInteger visitBudget) {
        if (visitBudget.get() >= diskHotspotVisitBudget) {
            return 0L;
        }
        visitBudget.incrementAndGet();
        try {
            if (Files.isRegularFile(path)) {
                return Files.size(path);
            }
            if (!Files.isDirectory(path) || remainingDepth < 1) {
                return 0L;
            }
            long sum = 0L;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                for (Path entry : ds) {
                    if (visitBudget.get() >= diskHotspotVisitBudget) {
                        break;
                    }
                    if (Files.isRegularFile(entry)) {
                        visitBudget.incrementAndGet();
                        sum += Files.size(entry);
                    } else if (Files.isDirectory(entry)) {
                        sum += recursiveDirectorySizeBytes(entry, remainingDepth - 1, visitBudget);
                    }
                }
            }
            return sum;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 异常日志基线：首轮播种；突增告警时不抬高基线；窗口回落时重置；常态下 EMA 平滑更新。
     */
    private void applyAnomalyBaseline(
            long anomalyTotal,
            Map<String, Object> correlation,
            List<Map<String, Object>> findings,
            long nowMs) {
        if (anomalyTotal < 0) {
            return;
        }

        long prev = lastAnomalyTotal;
        if (!anomalyBaselineInitialized) {
            lastAnomalyTotal = anomalyTotal;
            anomalyBaselineInitialized = true;
            correlation.put("anomalyBaseline", anomalyTotal);
            correlation.put("anomalyBaselineAction", "seed");
            return;
        }

        boolean windowShrink = prev > 0 && anomalyTotal < prev * 0.55;
        boolean spike = !windowShrink
                && prev > 0
                && anomalyTotal >= prev * anomalySpikeFactor
                && anomalyTotal - prev >= anomalyMinDelta;

        if (spike) {
            correlation.put("anomalyBaseline", prev);
            correlation.put("anomalyBaselineAction", "hold_on_spike");
            correlation.put("anomalyObserved", anomalyTotal);
            maybeAddDeduped(
                    finding("WARN", "log_anomaly", "异常日志量相对基线显著上升",
                            String.format("近 1 日约 %d 条（基线约 %d，增幅 %.1f%%）",
                                    anomalyTotal,
                                    prev,
                                    prev > 0 ? (anomalyTotal - prev) * 100.0 / prev : 0)),
                    findings,
                    nowMs,
                    true);
            return;
        }

        if (windowShrink) {
            lastAnomalyTotal = anomalyTotal;
            correlation.put("anomalyBaseline", anomalyTotal);
            correlation.put("anomalyBaselineAction", "reset_window_shrink");
            return;
        }

        double alpha = Math.min(1.0, Math.max(0.05, anomalyBaselineEmaAlpha));
        long updated = Math.round(prev * (1.0 - alpha) + anomalyTotal * alpha);
        lastAnomalyTotal = updated;
        correlation.put("anomalyBaseline", updated);
        correlation.put("anomalyBaselineAction", "ema_update");
        correlation.put("anomalyObserved", anomalyTotal);
    }

    private static Map<String, Object> finding(String level, String code, String title, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("code", code);
        m.put("title", title);
        m.put("detail", detail);
        return m;
    }

    private static double asDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    private static long asLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return 0L;
    }

    private int countZombieProcesses() {
        if (!OsRuntime.isUnixLike()) {
            return 0;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "sh", "-c", "ps -eo stat= 2>/dev/null | grep -c '^Z' || true");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String line;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = br.readLine();
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return 0;
            }
            if (line != null && !line.isBlank()) {
                return Integer.parseInt(line.trim());
            }
        } catch (Exception e) {
            log.debug("僵尸进程计数跳过: {}", e.getMessage());
        }
        return 0;
    }
}
