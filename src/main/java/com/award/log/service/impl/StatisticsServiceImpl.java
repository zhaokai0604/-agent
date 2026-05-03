package com.award.log.service.impl;

import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.mapper.LogAnalysisTaskMapper;
import com.award.log.service.StatisticsService;
import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 数据统计Service实现
 */
@Slf4j
@Service
public class StatisticsServiceImpl implements StatisticsService {

    @Autowired
    private LogAnalysisTaskMapper taskMapper;

    @Autowired
    private LogAnalysisDetailMapper detailMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 缓存键前缀
    private static final String CACHE_PREFIX = "statistics:";
    private static final String TASK_STATUS_KEY = CACHE_PREFIX + "task_status";
    private static final String LOG_SUMMARY_KEY_PREFIX = CACHE_PREFIX + "log_summary:v2:";

    // 缓存过期时间（10分钟）
    private static final long CACHE_EXPIRE_TIME = 10;

    // 网络使用率计算相关的静态变量，用于存储上次的网络传输字节数和时间戳
    private static long lastBytesRecv = 0;
    private static long lastBytesSent = 0;
    private static long lastNetworkTimestamp = 0;
    private static boolean networkInitialized = false;

    @Override
    public Map<String, Object> getRecentLogSummary(int days) {
        String cacheKey = LOG_SUMMARY_KEY_PREFIX + days;
        
        // 尝试从缓存获取
        try {
            Object cachedData = redisTemplate.opsForValue().get(cacheKey);
            if (cachedData != null && cachedData instanceof Map) {
                log.info("从缓存获取近期日志汇总，天数: [{}]", days);
                return (Map<String, Object>) cachedData;
            }
        } catch (Exception e) {
            log.warn("从缓存获取近期日志汇总失败: {}", e.getMessage());
        }

        log.info("从数据库获取近期日志汇总，天数: [{}]", days);
        Map<String, Object> summary = new HashMap<>();

        // 查询任务总数
        long totalTasks = taskMapper.countAll();
        summary.put("totalTasks", totalTasks);

        // 查询总日志数
        long totalLogs = detailMapper.countAll();
        summary.put("totalLogs", totalLogs);

        // 获取最近几天的任务趋势
        List<Map<String, Object>> taskTrend = taskMapper.getTaskTrend(days);
        summary.put("taskTrend", taskTrend);

        List<Map<String, Object>> anomalyDistribution = detailMapper.selectAnomalySeverityDistribution(days);
        summary.put("anomalyDistribution", anomalyDistribution);

        // 存入缓存
        try {
            redisTemplate.opsForValue().set(cacheKey, summary, CACHE_EXPIRE_TIME, TimeUnit.MINUTES);
            log.info("近期日志汇总存入缓存，天数: [{}]", days);
        } catch (Exception e) {
            log.warn("近期日志汇总存入缓存失败: {}", e.getMessage());
        }

        return summary;
    }

    // CPU使用率计算相关的静态变量
    private static long[] prevTicks = null;
    private static boolean initialized = false;

    @Override
    public Map<String, Object> getSystemPerformance(String preferredNetworkInterface) {
        // 每次调用都重新计算性能数据，确保返回最新数据
        log.info("获取系统性能数据");
        Map<String, Object> performance = new HashMap<>();
        long now = System.currentTimeMillis();

        try {
            // 使用oshi库获取真实的系统性能数据
            SystemInfo systemInfo = new SystemInfo();
            HardwareAbstractionLayer hal = systemInfo.getHardware();
            OperatingSystem os = systemInfo.getOperatingSystem();

            // 1. CPU使用率 (使用oshi库的方法，需要先获取基准值)
            CentralProcessor processor = hal.getProcessor();
            double cpuUsage = 0.0;
            try {
                // 获取CPU负载 ticks
                long[] ticks = processor.getSystemCpuLoadTicks();
                if (prevTicks != null) {
                    // 计算CPU使用率
                    double cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevTicks);
                    cpuUsage = cpuLoad * 100.0;
                    cpuUsage = Math.max(0, Math.min(100, cpuUsage));
                } else if (!initialized) {
                    // 第一次调用时，尝试使用Java自带的OperatingSystemMXBean获取CPU使用率
                    OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();
                    if (osMxBean instanceof com.sun.management.OperatingSystemMXBean) {
                        com.sun.management.OperatingSystemMXBean sunOsMxBean = (com.sun.management.OperatingSystemMXBean) osMxBean;
                        double cpuLoad = sunOsMxBean.getSystemCpuLoad();
                        if (cpuLoad >= 0) {
                            cpuUsage = cpuLoad * 100.0;
                            cpuUsage = Math.max(0, Math.min(100, cpuUsage));
                        }
                    }
                    initialized = true;
                }
                // 更新基准值
                prevTicks = ticks;
            } catch (Exception e) {
                log.warn("获取CPU使用率失败: {}", e.getMessage());
                // 尝试使用Java自带的OperatingSystemMXBean获取CPU使用率
                OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();
                if (osMxBean instanceof com.sun.management.OperatingSystemMXBean) {
                    com.sun.management.OperatingSystemMXBean sunOsMxBean = (com.sun.management.OperatingSystemMXBean) osMxBean;
                    double cpuLoad = sunOsMxBean.getSystemCpuLoad();
                    if (cpuLoad >= 0) {
                        cpuUsage = cpuLoad * 100.0;
                        cpuUsage = Math.max(0, Math.min(100, cpuUsage));
                    }
                }
            }
            performance.put("cpuUsage", cpuUsage);

            // 2. 内存使用率
            GlobalMemory memory = hal.getMemory();
            long totalMemory = memory.getTotal();
            long availableMemory = memory.getAvailable();
            long usedMemory = totalMemory - availableMemory;
            double memoryUsage = (double) usedMemory / totalMemory * 100.0;
            memoryUsage = Math.max(0, Math.min(100, memoryUsage));
            performance.put("memoryUsage", memoryUsage);
            performance.put("usedMemory", usedMemory);
            performance.put("totalMemory", totalMemory);
            performance.put("freeMemory", availableMemory);

            // 3. 磁盘使用率 (使用简单的方式计算)
            try {
                // 使用Java自带的方法获取磁盘使用情况
                FileStore fileStore = Files.getFileStore(Paths.get("."));
                long totalDiskSize = fileStore.getTotalSpace();
                long usableSpace = fileStore.getUsableSpace();
                long usedSpace = totalDiskSize - usableSpace;
                double diskUsage = (double) usedSpace / totalDiskSize * 100.0;
                diskUsage = Math.max(0, Math.min(100, diskUsage));
                
                performance.put("diskUsage", diskUsage);
                performance.put("diskPath", fileStore.toString());
                performance.put("diskTotal", totalDiskSize);
                performance.put("diskUsed", usedSpace);
                performance.put("diskFree", usableSpace);
            } catch (Exception e) {
                log.warn("获取磁盘使用情况失败: {}", e.getMessage());
                // 如果获取失败，使用默认值
                performance.put("diskUsage", 0.0);
                performance.put("diskPath", "");
            }

            // 4. 网络使用率 (计算单位时间内的网络传输速率与带宽的比值，更符合任务管理器的显示方式)
            List<NetworkIF> networkIFs = hal.getNetworkIFs();
            if (!networkIFs.isEmpty()) {
                // 刷新网卡统计快照，否则计数值可能长期不变
                for (NetworkIF nif : networkIFs) {
                    try {
                        nif.updateAttributes();
                    } catch (Exception ignored) {
                    }
                }

                List<String> interfaces = new ArrayList<>();
                networkIFs.forEach(nif -> interfaces.add(nif.getName()));
                performance.put("networkInterfaces", interfaces);

                // 优先按指定网卡匹配，否则取最活跃非环回网卡
                NetworkIF networkIF = null;
                if (preferredNetworkInterface != null && !preferredNetworkInterface.isBlank()) {
                    networkIF = networkIFs.stream()
                            .filter(nif -> preferredNetworkInterface.equalsIgnoreCase(nif.getName()))
                            .findFirst()
                            .orElse(null);
                }
                if (networkIF == null) {
                    networkIF = networkIFs.stream()
                            .filter(nif -> {
                                String name = (nif.getName() == null ? "" : nif.getName()).toLowerCase();
                                String display = (nif.getDisplayName() == null ? "" : nif.getDisplayName()).toLowerCase();
                                return !(name.contains("loopback") || name.equals("lo") || display.contains("loopback"));
                            })
                            .max(Comparator.comparingLong(nif -> nif.getBytesRecv() + nif.getBytesSent()))
                            .orElse(networkIFs.get(0));
                }
                long bytesRecv = networkIF.getBytesRecv();
                long bytesSent = networkIF.getBytesSent();
                long packetsRecv = networkIF.getPacketsRecv();
                long packetsSent = networkIF.getPacketsSent();
                long currentTimestamp = System.currentTimeMillis();
                
                // 计算网络使用率（基于单位时间内的传输速率）
                double networkUsage = 0.0;
                if (lastNetworkTimestamp > 0) {
                    // 计算时间差（秒）
                    double timeDiff = (currentTimestamp - lastNetworkTimestamp) / 1000.0;
                    if (timeDiff > 0) {
                        // 计算单位时间内的传输字节数
                        long deltaBytesRecv = bytesRecv - lastBytesRecv;
                        long deltaBytesSent = bytesSent - lastBytesSent;
                        long deltaTotalBytes = deltaBytesRecv + deltaBytesSent;
                        
                        // 计算每秒传输速率（字节/秒）
                        double bytesPerSecond = deltaTotalBytes / timeDiff;
                        
                        // 网络带宽（字节/秒）；取不到速率时使用 100Mbps 兜底，避免长期0
                        long speedMbps = networkIF.getSpeed() > 0 ? networkIF.getSpeed() : 100L;
                        long bandwidth = speedMbps * 1000000 / 8;
                        
                        // 网络使用率
                        networkUsage = (bytesPerSecond / bandwidth) * 100.0;
                        networkUsage = Math.max(0, Math.min(100, networkUsage));
                    }
                } else if (!networkInitialized) {
                    // 第一次调用时，使用一个默认值作为网络使用率
                    networkUsage = 0.0;
                    networkInitialized = true;
                }
                
                // 更新上次的网络传输字节数和时间戳
                lastBytesRecv = bytesRecv;
                lastBytesSent = bytesSent;
                lastNetworkTimestamp = currentTimestamp;
                
                performance.put("networkUsage", networkUsage);
                performance.put("networkInterface", networkIF.getName());
                performance.put("bytesRecv", bytesRecv);
                performance.put("bytesSent", bytesSent);
                performance.put("packetsRecv", packetsRecv);
                performance.put("packetsSent", packetsSent);
            } else {
                // 如果没有找到网络接口，使用默认值
                performance.put("networkUsage", 0.0);
                performance.put("networkInterface", "eth0");
                performance.put("networkInterfaces", Collections.emptyList());
            }

            // 获取线程数
            int threadCount = Thread.activeCount();
            performance.put("threadCount", threadCount);

            // 获取当前时间
            performance.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("获取系统性能数据失败: {}", e.getMessage(), e);
            // 保持真实口径：异常时返回0并标记错误，不再注入随机模拟值
            performance.put("cpuUsage", 0.0);
            performance.put("memoryUsage", 0.0);
            performance.put("diskUsage", 0.0);
            performance.put("networkUsage", 0.0);
            performance.put("networkInterface", "N/A");
            performance.put("networkInterfaces", Collections.emptyList());
            performance.put("threadCount", Thread.activeCount());
            performance.put("timestamp", System.currentTimeMillis());
            performance.put("error", "SYSTEM_METRICS_COLLECTION_FAILED");
        }

        return performance;
    }



    @Override
    public Map<String, Object> getTaskStatusStatistics() {
        // 尝试从缓存获取
        try {
            Object cachedData = redisTemplate.opsForValue().get(TASK_STATUS_KEY);
            if (cachedData != null && cachedData instanceof Map) {
                log.info("从缓存获取任务状态统计");
                return (Map<String, Object>) cachedData;
            }
        } catch (Exception e) {
            log.warn("从缓存获取任务状态统计失败: {}", e.getMessage());
        }

        log.info("从数据库获取任务状态统计");
        Map<String, Object> statistics = new HashMap<>();

        // 获取各状态任务数量
        List<Map<String, Object>> statusCountList = taskMapper.getTaskStatusCount();
        // 将List转换为Map
        Map<String, Integer> statusCount = new HashMap<>();
        for (Map<String, Object> item : statusCountList) {
            String status = (String) item.get("status");
            Long count = (Long) item.get("count");
            statusCount.put(status, count.intValue());
        }
        statistics.put("statusCount", statusCount);

        // 获取任务完成情况统计
        List<Map<String, Object>> completionStatistics = taskMapper.getTaskCompletionStatistics();
        statistics.put("completionStatistics", completionStatistics);

        // 获取平均处理时间
        Double avgProcessingTime = taskMapper.getAverageProcessingTime();
        statistics.put("avgProcessingTime", avgProcessingTime);

        // 存入缓存
        try {
            redisTemplate.opsForValue().set(TASK_STATUS_KEY, statistics, CACHE_EXPIRE_TIME, TimeUnit.MINUTES);
            log.info("任务状态统计存入缓存");
        } catch (Exception e) {
            log.warn("任务状态统计存入缓存失败: {}", e.getMessage());
        }

        return statistics;
    }



    @Override
    public Map<String, Object> getAnomalyLogStatistics(int days) {
        Map<String, Object> result = new HashMap<>();
        result.put("days", days);
        List<Map<String, Object>> distribution = detailMapper.selectAnomalySeverityDistribution(days);
        result.put("anomalyDistribution", distribution);
        long total = 0L;
        if (distribution != null) {
            for (Map<String, Object> row : distribution) {
                Object c = row.get("count");
                if (c instanceof Number) {
                    total += ((Number) c).longValue();
                }
            }
        }
        result.put("totalAnomalyLogs", total);
        return result;
    }
}