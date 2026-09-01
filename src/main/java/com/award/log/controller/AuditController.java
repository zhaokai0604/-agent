package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.UnifiedAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 统一审计流水只读入口（MCP/运维等异构记录汇聚）。
 * HTTP 前缀：{@code /api/audit}。
 * 不提供写入；详情需 {@code entryId} 或 {@code traceId}。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final UnifiedAuditService unifiedAuditService;

    public AuditController(UnifiedAuditService unifiedAuditService) {
        this.unifiedAuditService = unifiedAuditService;
    }

    @GetMapping("/feed")
    public Result<List<Map<String, Object>>> feed(@RequestParam(defaultValue = "100") int limit,
                                                  @RequestParam(required = false) String kind) {
        return Result.success(unifiedAuditService.feed(limit, kind));
    }

    @GetMapping("/detail")
    public Result<Map<String, Object>> detail(@RequestParam(required = false) String entryId,
                                              @RequestParam(required = false) String traceId) {
        Map<String, Object> detail = unifiedAuditService.detail(entryId, traceId);
        if (detail == null || detail.isEmpty()) {
            return Result.error(404, "未找到对应审计记录");
        }
        return Result.success(detail);
    }

    @GetMapping("/access/summary")
    public Result<Map<String, Object>> accessSummary(@RequestParam(defaultValue = "24") int hours,
                                                      @RequestParam(defaultValue = "hour") String granularity) {
        return Result.success(unifiedAuditService.accessSummary(hours, granularity));
    }
}
