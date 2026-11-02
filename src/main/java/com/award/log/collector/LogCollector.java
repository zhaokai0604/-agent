package com.award.log.collector;

import java.util.List;

/**
 * 日志采集器接口：start/stop 控制生命周期，{@link #collect()} 由调度器周期性排水。
 * 实现类内部缓冲必须有上限（参见 FileLogCollector / NetworkLogCollector），否则 collect 跟不上生产会 OOM。
 */
public interface LogCollector {

    /**
     * 开始采集日志
     */
    void start();

    /**
     * 停止采集日志
     */
    void stop();

    /**
     * 采集日志数据
     * @return 采集到的日志列表
     */
    List<String> collect();

    /** The caller may acknowledge only after a durable receiver has accepted the whole batch. */
    default CollectedLogBatch collectBatch() { return new CollectedLogBatch(collect(), () -> {}); }

    /**
     * 获取采集器名称
     * @return 采集器名称
     */
    String getName();

    /**
     * 检查采集器状态
     * @return 是否运行中
     */
    boolean isRunning();

    /** Read-only health counters; implementations must not drain the buffer. */
    default java.util.Map<String, Object> diagnostics() { return java.util.Map.of(); }
}
