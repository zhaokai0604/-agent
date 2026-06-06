package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.decision.RandomForestDecisionEngine;
import com.award.log.governance.OpsGovernanceService;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.platform.KylinCommandProbe;
import com.award.log.util.OsRuntime;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 验收用：平台/OS/架构与生产 profile 快照（不含密钥明文）。
 */
@Tag(name = "平台信息")
@RestController
@RequestMapping("/api/platform")
public class PlatformInfoController {

    private final Environment environment;
    private final RandomForestDecisionEngine randomForestDecisionEngine;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsGovernanceService opsGovernanceService;

    @Autowired(required = false)
    private KylinCommandProbe kylinCommandProbe;

    @Value("${platform.kylin.expected-arch:loongarch64}")
    private String expectedArch;

    public PlatformInfoController(
            Environment environment,
            RandomForestDecisionEngine randomForestDecisionEngine,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsGovernanceService opsGovernanceService) {
        this.environment = environment;
        this.randomForestDecisionEngine = randomForestDecisionEngine;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsGovernanceService = opsGovernanceService;
    }

    @Operation(summary = "平台与生产就绪快照（需登录；含治理与最小权限详情）")
    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        Map<String, Object> body = buildAcceptanceBody();

        String[] active = environment.getActiveProfiles();
        body.put("activeProfiles", active.length == 0 ? new String[]{"default"} : active);
        body.put("prodProfileActive", Arrays.stream(active).anyMatch(p -> p.contains("prod")));
        body.put("kylinProfileActive", Arrays.stream(active).anyMatch(p -> "kylin".equals(p)));

        body.put("randomForest", randomForestDecisionEngine.healthSnapshot());

        if (kylinCommandProbe != null) {
            body.put("kylinCommandProbe", kylinCommandProbe.getLastProbeResult());
        }

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("minPrivilegeEnabled", minPrivilegeExecutor.isMinPrivilegeEnabled());
        security.put("runAsUser", minPrivilegeExecutor.getRunAsUser());
        security.put("aiAuditRelaxedRead", environment.getProperty("app.security.ai-audit-relaxed-read"));
        security.put("autoRemediationEnabled", environment.getProperty("ops.auto-remediation.enabled"));
        security.put("autonomousOpsEnabled", environment.getProperty("agent.autonomous.enabled"));
        body.put("security", security);

        body.put("governance", opsGovernanceService.summaryForPlatform());

        return Result.success(body);
    }

    @Operation(summary = "麒麟 V11 + LoongArch 公开验收快照（无需登录，不含运行用户与自动化开关）")
    @GetMapping("/acceptance")
    public Result<Map<String, Object>> acceptance() {
        return Result.success(buildAcceptanceBody());
    }

    private Map<String, Object> buildAcceptanceBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", OsRuntime.platformSummary());

        Map<String, Object> acceptance = new LinkedHashMap<>();
        acceptance.put("expectedArch", expectedArch);
        acceptance.put("archMatch", OsRuntime.osArch().toLowerCase().contains(expectedArch.toLowerCase())
                || (OsRuntime.isLoongArch() && expectedArch.toLowerCase().contains("loong")));
        acceptance.put("kylinDetected", OsRuntime.isKylin());
        acceptance.put("loongArchDetected", OsRuntime.isLoongArch());
        acceptance.put("deliveryTargetMatch", OsRuntime.isKylin() && OsRuntime.isLoongArch());
        body.put("acceptance", acceptance);
        return body;
    }
}
