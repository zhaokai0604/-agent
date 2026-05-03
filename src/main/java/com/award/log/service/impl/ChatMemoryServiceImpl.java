package com.award.log.service.impl;

import com.award.log.service.ChatMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatMemoryServiceImpl implements ChatMemoryService {

    private final Map<String, List<MemoryEntry>> sessionMemories = new ConcurrentHashMap<>();
    private static final int MAX_MEMORY_SIZE = 100;

    @Override
    public void addToMemory(String sessionId, MemoryEntry entry) {
        sessionMemories.computeIfAbsent(sessionId, k -> new ArrayList<>());
        List<MemoryEntry> memory = sessionMemories.get(sessionId);
        memory.add(entry);

        if (memory.size() > MAX_MEMORY_SIZE) {
            memory.remove(0);
        }

        log.debug("记忆已添加至会话 {}: role={}, content长度={}", sessionId, entry.getRole(), entry.getContent().length());
    }

    @Override
    public List<MemoryEntry> getRecentMemory(String sessionId, int limit) {
        List<MemoryEntry> memory = sessionMemories.getOrDefault(sessionId, new ArrayList<>());
        int size = Math.min(limit, memory.size());
        return memory.subList(memory.size() - size, memory.size());
    }

    @Override
    public List<MemoryEntry> searchMemory(String sessionId, String keyword) {
        List<MemoryEntry> memory = sessionMemories.getOrDefault(sessionId, new ArrayList<>());
        return memory.stream()
                .filter(e -> e.getContent() != null && e.getContent().contains(keyword))
                .collect(Collectors.toList());
    }

    @Override
    public void clearMemory(String sessionId) {
        sessionMemories.remove(sessionId);
        log.info("会话 {} 的记忆已清除", sessionId);
    }

    @Override
    public MemorySummary getMemorySummary(String sessionId) {
        List<MemoryEntry> memory = sessionMemories.getOrDefault(sessionId, new ArrayList<>());
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
        return sessionMemories.getOrDefault(sessionId, new ArrayList<>()).size();
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