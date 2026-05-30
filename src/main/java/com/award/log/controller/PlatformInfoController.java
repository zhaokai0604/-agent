package com.award.log.controller;

import com.award.log.collector.LogCollectorPathResolver;
import com.award.log.common.Result;
import com.award.log.config.OpsDryRunProperties;
import com.award.log.decision.RandomForestDecisionEngine;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.platform.KylinCommandProbe;
import com.award.log.util.OsRuntime;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台与运行环境快照（不含密钥明文）。
 */
@Tag(name = "平台信息")
@RestController
@RequestMapping("/api/platform")
public class PlatformInfoController {

    private final Environment environment;
    private final RandomForestDecisionEngine randomForestDecisionEngine;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsDryRunProperties opsDryRunProperties;

    @Autowired(required = false)
    private ChatClient chatClient;

    @Value("${spring.ai.openai.api-key:}")
    private String aiApiKey;

    @Value("${log.collector.file.path:}")
    private String logCollectorPath;

    @Autowired(required = false)
    private KylinCommandProbe kylinCommandProbe;

    public PlatformInfoController(
            Environment environment,
            RandomForestDecisionEngine randomForestDecisionEngine,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsDryRunProperties opsDryRunProperties) {
        this.environment = environment;
        this.randomForestDecisionEngine = randomForestDecisionEngine;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsDryRunProperties = opsDryRunProperties;
    }

    @Operation(summary = "平台与运行环境快照")
    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", OsRuntime.platformSummary());

        String[] active = environment.getActiveProfiles();
        body.put("activeProfiles", active.length == 0 ? new String[]{"default"} : active);
        body.put("prodProfileActive", Arrays.stream(active).anyMatch(p -> p.contains("prod")));
        body.put("kylinProfileActive", Arrays.stream(active).anyMatch(p -> "kylin".equals(p)));

        Map<String, Object> platformDetails = new LinkedHashMap<>();
        platformDetails.put("arch", OsRuntime.osArch());
        platformDetails.put("kylin", OsRuntime.isKylin());
        platformDetails.put("loongArch", OsRuntime.isLoongArch());
        body.put("platformDetails", platformDetails);

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
        security.put("globalDryRun", opsDryRunProperties.isGlobalDryRun());
        security.put("aiConfigured", chatClient != null && aiApiKey != null && !aiApiKey.isBlank());
        body.put("security", security);

        Map<String, Object> runtime = new LinkedHashMap<>();
        List<String> collectorRoots = LogCollectorPathResolver.resolve(logCollectorPath);
        boolean collectorAuto = LogCollectorPathResolver.isAutoMode(logCollectorPath);
        runtime.put("logCollectorMode", collectorAuto ? "auto" : "manual");
        runtime.put("logCollectorPaths", collectorRoots);
        runtime.put("logCollectorPath", LogCollectorPathResolver.displaySummary(logCollectorPath, collectorRoots));
        runtime.put("dataSource", "live");
        body.put("runtime", runtime);

        return Result.success(body);
    }
}
