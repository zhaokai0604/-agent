package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.LogDocument;
import com.award.log.service.ElasticsearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Elasticsearch控制器
 * 用于暴露Elasticsearch的查询和管理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/elasticsearch")
public class ElasticsearchController {

    @Autowired(required = false)
    private ElasticsearchService elasticsearchService;

    /**
     * 索引单条日志
     * @param logDocument 日志文档
     * @return 操作结果
     */
    @PostMapping("/index")
    public Result<LogDocument> indexLog(@RequestBody LogDocument logDocument) {
        try {
            if (elasticsearchService != null) {
                LogDocument result = elasticsearchService.indexLog(logDocument);
                return Result.success(result, "索引日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("索引日志失败", e);
            return Result.error("索引日志失败: " + e.getMessage());
        }
    }

    /**
     * 批量索引日志
     * @param logDocuments 日志文档列表
     * @return 操作结果
     */
    @PostMapping("/bulk-index")
    public Result<Integer> bulkIndexLogs(@RequestBody List<LogDocument> logDocuments) {
        try {
            if (elasticsearchService != null) {
                int count = elasticsearchService.bulkIndexLogs(logDocuments);
                return Result.success(count, "批量索引日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("批量索引日志失败", e);
            return Result.error("批量索引日志失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有日志（分页）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 操作结果
     */
    @GetMapping("/logs")
    public Result<Page<LogDocument>> getLogs(@RequestParam(defaultValue = "1") int pageNum, 
                                           @RequestParam(defaultValue = "10") int pageSize) {
        try {
            if (elasticsearchService != null) {
                // 这里可以根据实际需求实现获取所有日志的逻辑
                // 暂时使用搜索接口，传入空查询
                Pageable pageable = PageRequest.of(pageNum - 1, pageSize);
                Page<LogDocument> result = elasticsearchService.searchLogs("", pageable);
                return Result.success(result, "获取日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("获取日志失败", e);
            return Result.error("获取日志失败: " + e.getMessage());
        }
    }

    /**
     * 根据ID查询日志
     * @param id 日志ID
     * @return 操作结果
     */
    @GetMapping("/get/{id}")
    public Result<LogDocument> getLogById(@PathVariable String id) {
        try {
            if (elasticsearchService != null) {
                LogDocument result = elasticsearchService.getLogById(id);
                return Result.success(result, "查询日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("查询日志失败", e);
            return Result.error("查询日志失败: " + e.getMessage());
        }
    }

    /**
     * 全文检索日志
     * @param query 搜索关键词
     * @param severity 日志级别
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 操作结果
     */
    @GetMapping("/search")
    public Result<Page<LogDocument>> searchLogs(@RequestParam(required = false, defaultValue = "") String query,
                                                @RequestParam(required = false) String severity, 
                                                @RequestParam(required = false) Boolean anomaly,
                                                @RequestParam(required = false) String startTime,
                                                @RequestParam(required = false) String endTime,
                                                @RequestParam(defaultValue = "1") int pageNum, 
                                                @RequestParam(defaultValue = "10") int pageSize) {
        try {
            if (elasticsearchService != null) {
                Pageable pageable = PageRequest.of(pageNum - 1, pageSize);
                LocalDateTime start = (startTime == null || startTime.isEmpty()) ? null : LocalDateTime.parse(startTime);
                LocalDateTime end = (endTime == null || endTime.isEmpty()) ? null : LocalDateTime.parse(endTime);
                Page<LogDocument> result = elasticsearchService.searchLogs(query, severity, anomaly, start, end, pageable);
                return Result.success(result, "搜索日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("搜索日志失败", e);
            return Result.error("搜索日志失败: " + e.getMessage());
        }
    }

    /**
     * 根据时间范围查询日志
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param page 页码
     * @param size 每页大小
     * @return 操作结果
     */
    @GetMapping("/time-range")
    public Result<Page<LogDocument>> getLogsByTimeRange(@RequestParam String startTime, 
                                                        @RequestParam String endTime, 
                                                        @RequestParam(defaultValue = "0") int page, 
                                                        @RequestParam(defaultValue = "10") int size) {
        try {
            if (elasticsearchService != null) {
                LocalDateTime start = LocalDateTime.parse(startTime);
                LocalDateTime end = LocalDateTime.parse(endTime);
                Pageable pageable = PageRequest.of(page, size);
                Page<LogDocument> result = elasticsearchService.getLogsByTimeRange(start, end, pageable);
                return Result.success(result, "按时间范围查询日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("按时间范围查询日志失败", e);
            return Result.error("按时间范围查询日志失败: " + e.getMessage());
        }
    }

    /**
     * 根据严重程度查询日志
     * @param severity 严重程度
     * @param page 页码
     * @param size 每页大小
     * @return 操作结果
     */
    @GetMapping("/severity")
    public Result<Page<LogDocument>> getLogsBySeverity(@RequestParam String severity, 
                                                      @RequestParam(defaultValue = "0") int page, 
                                                      @RequestParam(defaultValue = "10") int size) {
        try {
            if (elasticsearchService != null) {
                Pageable pageable = PageRequest.of(page, size);
                Page<LogDocument> result = elasticsearchService.getLogsBySeverity(severity, pageable);
                return Result.success(result, "按严重程度查询日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("按严重程度查询日志失败", e);
            return Result.error("按严重程度查询日志失败: " + e.getMessage());
        }
    }

    /**
     * 根据任务ID查询日志
     * @param taskId 任务ID
     * @param page 页码
     * @param size 每页大小
     * @return 操作结果
     */
    @GetMapping("/task/{taskId}")
    public Result<Page<LogDocument>> getLogsByTaskId(@PathVariable String taskId, 
                                                    @RequestParam(defaultValue = "0") int page, 
                                                    @RequestParam(defaultValue = "10") int size) {
        try {
            if (elasticsearchService != null) {
                Pageable pageable = PageRequest.of(page, size);
                Page<LogDocument> result = elasticsearchService.getLogsByTaskId(taskId, pageable);
                return Result.success(result, "按任务ID查询日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("按任务ID查询日志失败", e);
            return Result.error("按任务ID查询日志失败: " + e.getMessage());
        }
    }

    /**
     * 查询异常日志
     * @param page 页码
     * @param size 每页大小
     * @return 操作结果
     */
    @GetMapping("/anomaly")
    public Result<Page<LogDocument>> getAnomalyLogs(@RequestParam(defaultValue = "0") int page, 
                                                   @RequestParam(defaultValue = "10") int size) {
        try {
            if (elasticsearchService != null) {
                Pageable pageable = PageRequest.of(page, size);
                Page<LogDocument> result = elasticsearchService.getAnomalyLogs(pageable);
                return Result.success(result, "查询异常日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("查询异常日志失败", e);
            return Result.error("查询异常日志失败: " + e.getMessage());
        }
    }

    /**
     * 删除指定时间范围之前的日志
     * @param beforeTime 时间点
     * @return 操作结果
     */
    @DeleteMapping("/delete/before")
    public Result<Long> deleteLogsBefore(@RequestParam String beforeTime) {
        try {
            if (elasticsearchService != null) {
                LocalDateTime time = LocalDateTime.parse(beforeTime);
                long count = elasticsearchService.deleteLogsBefore(time);
                return Result.success(count, "删除日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("删除日志失败", e);
            return Result.error("删除日志失败: " + e.getMessage());
        }
    }

    /**
     * 删除指定任务的日志
     * @param taskId 任务ID
     * @return 操作结果
     */
    @DeleteMapping("/delete/task/{taskId}")
    public Result<Long> deleteLogsByTaskId(@PathVariable String taskId) {
        try {
            if (elasticsearchService != null) {
                long count = elasticsearchService.deleteLogsByTaskId(taskId);
                return Result.success(count, "删除任务日志成功");
            } else {
                return Result.error("Elasticsearch服务未启用");
            }
        } catch (Exception e) {
            log.error("删除任务日志失败", e);
            return Result.error("删除任务日志失败: " + e.getMessage());
        }
    }
}
