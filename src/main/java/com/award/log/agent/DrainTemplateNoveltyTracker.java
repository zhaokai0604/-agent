package com.award.log.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内记录 Drain 模板 ID 的首次出现时间，用于「近期新模板」统计与 Agent 上下文提示。
 * 使用 {@link java.util.concurrent.ConcurrentHashMap#putIfAbsent}，可被日志分析与助手并发调用。
 * 非持久化：进程重启后首次见到的模板会再次计为 novel。
 */
@Component
public class DrainTemplateNoveltyTracker {

    private final ConcurrentHashMap<String, Long> firstSeenEpochMs = new ConcurrentHashMap<>();

    /**
     * 在一次分析运行中登记模板 ID，返回本次运行中「全局首次见到」的个数。
     */
    public int registerFromTemplateIds(Iterable<String> templateIds) {
        if (templateIds == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int novelInBatch = 0;
        for (String id : templateIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            Long prev = firstSeenEpochMs.putIfAbsent(id.trim(), now);
            if (prev == null) {
                novelInBatch++;
            }
        }
        return novelInBatch;
    }

    /** 过去 windowMs 内首次出现的模板种类数（用于巡检 / 助手上下文）。 */
    public long countFirstSeenInWindow(long windowMs) {
        long cutoff = System.currentTimeMillis() - Math.max(60_000L, windowMs);
        return firstSeenEpochMs.values().stream().filter(ts -> ts >= cutoff).count();
    }

    /** 供助手 / 巡检注入的轻量快照：已跟踪种类数 + 近 1 小时新模板数。 */
    public Map<String, Object> snapshotForContext() {
        long hourMs = 3_600_000L;
        return Map.of(
                "distinctTemplatesTracked", firstSeenEpochMs.size(),
                "novelTemplateKindsLastHour", countFirstSeenInWindow(hourMs)
        );
    }
}
