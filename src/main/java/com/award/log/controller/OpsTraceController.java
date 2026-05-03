package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.OpsAuditTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运维推理链路审计查询（赛题「可追溯」）。
 */
@RestController
@RequestMapping("/api/ops-trace")
@RequiredArgsConstructor
public class OpsTraceController {

    private final OpsAuditTraceService opsAuditTraceService;

    @GetMapping("/recent")
    public Result<List<Map<String, Object>>> recent(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(opsAuditTraceService.listRecent(limit));
    }
}
