package com.award.log.service.impl;

import com.award.log.service.ChatMemoryService;
import com.award.log.service.ChatMemoryService.MemoryEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 按 session 保存对话记忆（工具结果、意图摘要等），供后续轮次注入 prompt。
 * <p>
 * 必须监听 {@link ChatSessionRetention} 淘汰事件并删除 {@code sessionMemories}，
 * 否则会出现「索引已淘汰、记忆 Map 仍占堆」的孤儿泄漏。
 */
@Slf4j
@Service
@Deprecated(since = "delivery-2026-09", forRemoval = false)
@ConditionalOnProperty(prefix = "app.ai.legacy", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ChatMemoryServiceImpl implements ChatMemoryService {

    private final Map<String, List<MemoryEntry>> sessionMemories = new ConcurrentHashMap<>();
    private static final int MAX_MEMORY_SIZE = 100;

    @Autowired
    private ChatSessionRetention sessionRetention;

    /**
     * 注册 retention 淘汰回调：session 被踢时同步删本类记忆 Map。
     * 若不注册，索引淘汰后数据仍留在堆上，造成孤儿内存泄漏。
     */
    @PostConstruct
    void registerRetentionListener() {
        sessionRetention.addEvictionListener(sessionMemories::remove);
    }

    /** 追加一条记忆并 touch session；超出 {@link #MAX_MEMORY_SIZE} 丢最旧条目。 */
    @Override
    public void addToMemory(String sessionId, MemoryEntry entry) {
        sessionRetention.touch(sessionId);
        List<MemoryEntry> memory = sessionMemories.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        memory.add(entry);
        trim(memory, MAX_MEMORY_SIZE);
        log.debug("记忆已添加至会话 {}: role={}, content长度={}", sessionId, entry.getRole(), entry.getContent().length());
    }

    @Override
    public List<MemoryEntry> getRecentMemory(String sessionId, int limit) {
        List<MemoryEntry> memory = sessionMemories.getOrDefault(sessionId, List.of());
        if (memory.isEmpty()) {
            return List.of();
        }
        int size = Math.min(limit, memory.size());
        return List.copyOf(memory.subList(memory.size() - size, memory.size()));
    }

    @Override
    public List<MemoryEntry> searchMemory(String sessionId, String keyword) {
        List<MemoryEntry> memory = sessionMemories.getOrDefault(sessionId, List.of());
        return memory.stream()
                .filter(e -> e.getContent() != null && e.getContent().contains(keyword))
                .collect(Collectors.toList());
    }

    @Override
    public void clearMemory(String sessionId) {
        sessionMemories.remove(sessionId);
        sessionRetention.forget(sessionId);
        log.info("会话 {} 的记忆已清除", sessionId);
    }

    /** 定时按 TTL 淘汰过期 session，并删本类记忆，避免孤儿条目。 */
    @Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
    public void evictStaleSessions() {
        sessionRetention.evictExpired(sessionId -> sessionMemories.remove(sessionId));
    }

    @Override
    public MemorySummary getMemorySummary(String sessionId) {
        List<MemoryEntry> memory = sessionMemories.getOrDefault(sessionId, List.of());
        MemorySummary summary = new MemorySummary();
        summary.setTotalEntries(memory.size());
        summary.setAnalysisCount((int) memory.stream().filter(MemoryEntry::isAnalysis).count());

        if (!memory.isEmpty()) {
            MemoryEntry last = memory.get(memory.size() - 1);
            summary.setLastIntent(last.getIntent());
            summary.setLastActivity(last.getTimestamp());
            summary.setLastTopic(extractTopic(last.getContent()));
        }

        return summary;
    }

    @Override
    public int getMemorySize(String sessionId) {
        return sessionMemories.getOrDefault(sessionId, List.of()).size();
    }

    private static void trim(List<MemoryEntry> memory, int maxSize) {
        while (memory.size() > maxSize) {
            memory.remove(0);
        }
    }

    private String extractTopic(String content) {
        if (content == null || content.isEmpty()) {
            return "未知";
        }
        String[] words = content.split("\\s+");
        if (words.length <= 5) {
            return content;
        }
        return String.join(" ", Arrays.copyOf(words, 5)) + "...";
    }
}
