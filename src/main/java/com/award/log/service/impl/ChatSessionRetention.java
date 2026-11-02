package com.award.log.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程内聊天会话的 TTL + 总量上限（retention）。
 * <p>
 * <b>为什么需要 listener：</b>
 * 以前 {@link #touch(String)} 溢出时只删本类的 {@code lastAccessEpochMs}，
 * 不删 {@code ChatAnalysisServiceImpl} / {@code ChatMemoryServiceImpl} 里真正的历史 Map，
 * 导致「索引已淘汰、数据还在堆上」的孤儿泄漏。
 * 现在淘汰时会通知已注册的 listener，把业务侧缓存一并清掉。
 */
@Slf4j
@Component
public class ChatSessionRetention {

    /** 同时保留的最大 session 数；超出淘汰最久未访问的 */
    public static final int MAX_SESSIONS = 256;
    /** 超过该空闲时间的 session 视为过期（默认 24 小时） */
    public static final long SESSION_TTL_MS = 24L * 60 * 60 * 1000;

    /** sessionId → 最近访问时间（毫秒） */
    private final Map<String, Long> lastAccessEpochMs = new ConcurrentHashMap<>();
    /** 淘汰时回调：由 ChatAnalysis / ChatMemory 等注册，用来清自己的 Map */
    private final CopyOnWriteArrayList<Consumer<String>> evictionListeners = new CopyOnWriteArrayList<>();

    /** 注册淘汰监听；listener 收到的是被踢掉的 sessionId。 */
    public void addEvictionListener(Consumer<String> listener) {
        if (listener != null) {
            evictionListeners.add(listener);
        }
    }

    /** 当前仍在 retention 索引中的 session 数量（测试/监控用）。 */
    public int trackedSessionCount() {
        return lastAccessEpochMs.size();
    }

    /**
     * 标记 session 刚被使用；若超过 {@link #MAX_SESSIONS} 则立刻淘汰最旧的。
     * 淘汰会通知全部 listener（即使本方法没有额外 callback）。
     */
    public void touch(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        lastAccessEpochMs.put(sessionId, System.currentTimeMillis());
        trimOverflow(null);
    }

    /** 主动遗忘某个 session（仅删索引；业务 Map 应由调用方或 listener 清理）。 */
    public void forget(String sessionId) {
        if (sessionId != null) {
            lastAccessEpochMs.remove(sessionId);
        }
    }

    /**
     * 清理超过 TTL 的 session，并可附带一次性 callback。
     * 定时任务一般传「删掉自己 Map」的 lambda；listener 也会一并通知。
     */
    public void evictExpired(Consumer<String> onEvict) {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastAccessEpochMs.entrySet()) {
            if (now - e.getValue() > SESSION_TTL_MS) {
                expired.add(e.getKey());
            }
        }
        for (String sessionId : expired) {
            lastAccessEpochMs.remove(sessionId);
            notifyEvicted(sessionId, onEvict);
        }
        if (!expired.isEmpty()) {
            log.info("会话 retention 清理过期 session {} 个", expired.size());
        }
        trimOverflow(onEvict);
    }

    /**
     * 超出 MAX_SESSIONS 时按「最久未访问」淘汰。
     * 时间戳相同时用 sessionId 做稳定排序，避免 ConcurrentHashMap 无序导致淘汰结果抖动。
     */
    private void trimOverflow(Consumer<String> onEvict) {
        int overflow = lastAccessEpochMs.size() - MAX_SESSIONS;
        if (overflow <= 0) {
            return;
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(lastAccessEpochMs.entrySet());
        sorted.sort(Comparator
                .<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey));
        for (int i = 0; i < overflow && i < sorted.size(); i++) {
            String sessionId = sorted.get(i).getKey();
            lastAccessEpochMs.remove(sessionId);
            notifyEvicted(sessionId, onEvict);
        }
        log.info("会话 retention 淘汰最旧 session {} 个", overflow);
    }

    /** 先调本次附带 callback，再广播给所有长期注册的 listener。 */
    private void notifyEvicted(String sessionId, Consumer<String> extra) {
        if (extra != null) {
            try {
                extra.accept(sessionId);
            } catch (Exception e) {
                log.warn("session eviction callback failed id={}: {}", sessionId, e.getMessage());
            }
        }
        for (Consumer<String> listener : evictionListeners) {
            try {
                listener.accept(sessionId);
            } catch (Exception e) {
                log.warn("session eviction listener failed id={}: {}", sessionId, e.getMessage());
            }
        }
    }
}
