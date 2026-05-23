package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.OpsAutoRemediationService;
import com.award.log.service.OpsPatrolService;
import com.award.log.service.PatrolHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Ops Patrol")
@RestController
@RequestMapping("/api/ops/patrol")
public class OpsPatrolController {

    @Autowired
    private OpsPatrolService opsPatrolService;

    @Autowired(required = false)
    private OpsAutoRemediationService opsAutoRemediationService;

    @Autowired(required = false)
    private PatrolHistoryService patrolHistoryService;

    @Operation(summary = "巡检历史记录（持久化，默认近 7 天）")
    @GetMapping("/history")
    public Result<List<Map<String, Object>>> patrolHistory(
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (patrolHistoryService == null) {
            return Result.success(List.of());
        }
        return Result.success(patrolHistoryService.listHistory(days, limit));
    }

    @Operation(summary = "巡检告警频次趋势（按日聚合 finding_count）")
    @GetMapping("/history/trend")
    public Result<List<Map<String, Object>>> patrolTrend(
            @RequestParam(name = "days", defaultValue = "7") int days) {
        if (patrolHistoryService == null) {
            return Result.success(List.of());
        }
        return Result.success(patrolHistoryService.countByDay(days));
    }

    @Operation(summary = "最近一次巡检生成的多维关联快照（每轮更新，无告警时亦有数据）")
    @GetMapping("/correlation/latest")
    public Result<Map<String, Object>> latestCorrelation() {
        return Result.success(opsPatrolService.getLastCorrelationSnapshot());
    }

    @Operation(summary = "最近主动巡检产生的告警条目（内存环形，登录后可读）")
    @GetMapping("/alerts/recent")
    public Result<List<Map<String, Object>>> recentAlerts(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        int n = Math.min(50, Math.max(1, limit));
        return Result.success(opsPatrolService.getRecentAlerts(n));
    }

    @Operation(summary = "最近一次巡检触发的自动修复摘要（未启用或无动作时可能为空 Map）")
    @GetMapping("/remediation/last")
    public Result<Map<String, Object>> lastRemediation() {
        if (opsAutoRemediationService == null) {
            return Result.success(Map.of());
        }
        return Result.success(opsAutoRemediationService.getLastSummary());
    }

    @Operation(summary = "最近一轮巡检：各条线索的修复车道（AUTO/CONFIRM/MIXED/MANUAL/NONE）")
    @GetMapping("/remediation/coverage")
    public Result<List<Map<String, Object>>> remediationCoverage() {
        if (opsAutoRemediationService == null) {
            return Result.success(List.of());
        }
        return Result.success(opsAutoRemediationService.getRemediationCoverage());
    }

    @Operation(summary = "待确认的自动修复方案（安全护栏；无待确认时 hasPending=false）")
    @GetMapping("/remediation/pending")
    public Result<Map<String, Object>> remediationPending() {
        if (opsAutoRemediationService == null) {
            return Result.success(Map.of("hasPending", false));
        }
        return Result.success(opsAutoRemediationService.getPendingProposalView());
    }

    @Operation(summary = "确认并执行待处理修复方案，confirmCode 须为「确认执行」")
    @PostMapping("/remediation/confirm")
    public Result<Map<String, Object>> remediationConfirm(
            @RequestBody(required = false) Map<String, String> body) {
        if (opsAutoRemediationService == null) {
            return Result.success(Map.of("success", false, "error", "自动修复服务未启用"));
        }
        Map<String, String> b = body != null ? body : Map.of();
        String proposalId = b.get("proposalId");
        String confirmCode = b.get("confirmCode");
        return Result.success(opsAutoRemediationService.confirmPending(proposalId, confirmCode));
    }
}
