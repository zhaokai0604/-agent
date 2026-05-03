package com.award.log.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
public class ThreadPoolConfig {

    // 内存使用率阈值
    private static final double MEMORY_THRESHOLD_HIGH = 0.8;
    private static final double MEMORY_THRESHOLD_MEDIUM = 0.6;
    private static final double MEMORY_THRESHOLD_LOW = 0.4;

    /**
     * 检查当前内存使用情况
     */
    private double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        return (double) usedMemory / maxMemory;
    }

    /**
     * 基于内存使用情况和处理器数量计算自适应的核心线程数
     */
    private int calculateAdaptiveCorePoolSize(String poolName) {
        int processors = Runtime.getRuntime().availableProcessors();
        double memoryUsage = getMemoryUsage();
        int baseCoreSize;
        
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            // 内存使用高，减少线程数
            baseCoreSize = Math.max(1, processors);
            log.info("[{}] 内存使用率高 ({}), 核心线程数设置为: {}", poolName, String.format("%.2f", memoryUsage), baseCoreSize);
        } else if (memoryUsage > MEMORY_THRESHOLD_MEDIUM) {
            // 内存使用中等，适度线程数
            baseCoreSize = Math.min(16, processors * 2);
            log.info("[{}] 内存使用率中等 ({}), 核心线程数设置为: {}", poolName, String.format("%.2f", memoryUsage), baseCoreSize);
        } else {
            // 内存使用低，增加线程数
            baseCoreSize = Math.min(32, processors * 4);
            log.info("[{}] 内存使用率低 ({}), 核心线程数设置为: {}", poolName, String.format("%.2f", memoryUsage), baseCoreSize);
        }
        
        return baseCoreSize;
    }

    /**
     * 基于内存使用情况计算自适应的最大线程数
     */
    private int calculateAdaptiveMaxPoolSize(String poolName, int corePoolSize) {
        double memoryUsage = getMemoryUsage();
        int maxPoolSize;
        
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            // 内存使用高，限制最大线程数
            maxPoolSize = Math.min(corePoolSize * 2, 16);
        } else if (memoryUsage > MEMORY_THRESHOLD_MEDIUM) {
            // 内存使用中等，适度最大线程数
            maxPoolSize = Math.min(corePoolSize * 3, 24);
        } else {
            // 内存使用低，增加最大线程数
            maxPoolSize = Math.min(corePoolSize * 4, 48);
        }
        
        log.info("[{}] 最大线程数设置为: {}", poolName, maxPoolSize);
        return maxPoolSize;
    }

    /**
     * 基于内存使用情况计算自适应的队列容量
     */
    private int calculateAdaptiveQueueCapacity(String poolName) {
        double memoryUsage = getMemoryUsage();
        int queueCapacity;
        
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            // 内存使用高，减少队列容量
            queueCapacity = 50;
        } else if (memoryUsage > MEMORY_THRESHOLD_MEDIUM) {
            // 内存使用中等，适度队列容量
            queueCapacity = 200;
        } else {
            // 内存使用低，增加队列容量
            queueCapacity = 500;
        }
        
        log.info("[{}] 队列容量设置为: {}", poolName, queueCapacity);
        return queueCapacity;
    }

    /**
     * 用于处理 SSE (Server-Sent Events) 的线程池
     */
    @Bean("sseTaskExecutor")
    public ThreadPoolTaskExecutor sseTaskExecutor() {
        log.info("初始化 SSE 任务线程池...");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        int corePoolSize = calculateAdaptiveCorePoolSize("SSE");
        int maxPoolSize = calculateAdaptiveMaxPoolSize("SSE", corePoolSize);
        int queueCapacity = calculateAdaptiveQueueCapacity("SSE");
        
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("sse-executor-");
        // 拒绝策略：让调用方线程执行，避免任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 用于执行日志分析核心算法的线程池
     */
    @Bean("logAnalysisExecutor")
    public ThreadPoolTaskExecutor logAnalysisExecutor() {
        log.info("初始化日志分析线程池...");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        int corePoolSize = calculateAdaptiveCorePoolSize("LogAnalysis");
        int maxPoolSize = calculateAdaptiveMaxPoolSize("LogAnalysis", corePoolSize);
        int queueCapacity = calculateAdaptiveQueueCapacity("LogAnalysis");
        
        log.info("初始化日志分析线程池, 核心线程数: [{}], 最大线程数: [{}], 队列容量: [{}]", 
                corePoolSize, maxPoolSize, queueCapacity);
        
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("LogProcess-Thread-");
        // 拒绝策略：让调用方线程执行，避免任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}