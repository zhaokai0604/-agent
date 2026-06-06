package com.award.log.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class OpsGovernanceService {

    private final OpsGovernanceProperties properties;

    public OpsGovernanceService(OpsGovernanceProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Map<String, Object> summaryForPlatform() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("deliveryProfile", properties.getDeliveryProfile());
        out.put("defaultPathTier", properties.getDefaultPathTier().name());
        out.put("defaultServiceTier", properties.getDefaultServiceTier().name());
        out.put("serviceTierCount", properties.getServiceTiers().size());
        out.put("pathTierCount", properties.getPathTiers().size());
        out.put("actionMatrix", actionMatrixSummary());
        out.put("note", "仅 NON_CORE 上的临时/日志清理可自动；服务重启默认须确认；FORBIDDEN_AUTO 不纳入方案。");
        return out;
    }

    public List<Map<String, Object>> filterPlanSteps(List<Map<String, Object>> steps) {
        if (!properties.isEnabled() || steps == null || steps.isEmpty()) {
            return steps == null ? List.of() : steps;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            GovernanceEvaluation eval = evaluateStep(step);
            if (eval.verdict() == GovernanceAdmissionVerdict.FORBIDDEN) {
                log.info("[治理] 剔除步骤 kind={} target={} : {}", step.get("kind"), eval.target(), eval.reason());
                continue;
            }
            Map<String, Object> tagged = new LinkedHashMap<>(step);
            tagged.put("governanceVerdict", eval.verdict().name());
            tagged.put("governanceTier", eval.assetTier().name());
            tagged.put("governanceReason", eval.reason());
            out.add(tagged);
        }
        return out;
    }

    public GovernanceEvaluation evaluateStep(Map<String, Object> step) {
        OpsActionType action = OpsActionType.fromStepKind(String.valueOf(step.getOrDefault("kind", "")));
        if (action == null) {
            return new GovernanceEvaluation(GovernanceAdmissionVerdict.FORBIDDEN, AssetTier.FORBIDDEN_AUTO, "",
                    "未知步骤类型");
        }
        if (!properties.isEnabled()) {
            return new GovernanceEvaluation(GovernanceAdmissionVerdict.ALLOW_AUTO, AssetTier.NON_CORE, "",
                    "治理未启用");
        }

        AssetTier tier;
        String target;
        if (action == OpsActionType.SERVICE_RESTART) {
            target = normalizeServiceName(String.valueOf(step.getOrDefault("serviceName", "")));
            tier = resolveServiceTier(target);
        } else {
            target = String.valueOf(step.getOrDefault("path", ""));
            tier = resolvePathTier(target);
        }

        if (tier == AssetTier.FORBIDDEN_AUTO) {
            return new GovernanceEvaluation(
                    GovernanceAdmissionVerdict.FORBIDDEN,
                    tier,
                    target,
                    "资产分级 FORBIDDEN_AUTO，禁止自动/待确认方案");
        }

        OpsGovernanceProperties.ActionAdmissionRule rule = ruleFor(action);
        if (rule == null || rule.getAllowedTiers() == null || !rule.getAllowedTiers().contains(tier)) {
            return new GovernanceEvaluation(
                    GovernanceAdmissionVerdict.FORBIDDEN,
                    tier,
                    target,
                    "动作 " + action.name() + " 不允许作用于分级 " + tier.name());
        }

        if (!rule.isAutoAllowed()) {
            return new GovernanceEvaluation(
                    GovernanceAdmissionVerdict.CONFIRM_ONLY,
                    tier,
                    target,
                    "动作 " + action.name() + " 默认须确认后执行");
        }

        return new GovernanceEvaluation(
                GovernanceAdmissionVerdict.ALLOW_AUTO,
                tier,
                target,
                "准入通过：低风险可进入 HYBRID 自动车道");
    }

    public AssetTier resolveServiceTier(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return properties.getDefaultServiceTier();
        }
        String key = normalizeServiceName(serviceName);
        AssetTier tier = properties.getServiceTiers().get(key);
        return tier != null ? tier : properties.getDefaultServiceTier();
    }

    public AssetTier resolvePathTier(String path) {
        if (path == null || path.isBlank()) {
            return properties.getDefaultPathTier();
        }
        String normalized = normalizePath(path);
        AssetTier best = null;
        int bestLen = -1;
        for (Map.Entry<String, AssetTier> e : properties.getPathTiers().entrySet()) {
            String prefix = normalizePath(e.getKey());
            if (normalized.equals(prefix) || normalized.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")
                    || (prefix.length() > 1 && normalized.startsWith(prefix))) {
                if (prefix.length() > bestLen) {
                    bestLen = prefix.length();
                    best = e.getValue();
                }
            }
        }
        return best != null ? best : properties.getDefaultPathTier();
    }

    private OpsGovernanceProperties.ActionAdmissionRule ruleFor(OpsActionType action) {
        String key = switch (action) {
            case TEMP_CLEANUP -> "temp-cleanup";
            case LOG_CLEANUP -> "log-cleanup";
            case SERVICE_RESTART -> "service-restart";
        };
        return properties.getActions().get(key);
    }

    private List<Map<String, Object>> actionMatrixSummary() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, OpsGovernanceProperties.ActionAdmissionRule> e : properties.getActions().entrySet()) {
            OpsGovernanceProperties.ActionAdmissionRule rule = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", e.getKey());
            row.put("allowedTiers", rule.getAllowedTiers().stream().map(Enum::name).toList());
            row.put("autoAllowed", rule.isAutoAllowed());
            row.put("verifyHint", rule.getVerifyHint());
            row.put("rollbackHint", rule.getRollbackHint());
            rows.add(row);
        }
        return rows;
    }

    private static String normalizeServiceName(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(".service", "");
    }

    private static String normalizePath(String raw) {
        if (raw == null) {
            return "";
        }
        String p = raw.trim().replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public record GovernanceEvaluation(
            GovernanceAdmissionVerdict verdict,
            AssetTier assetTier,
            String target,
            String reason
    ) {
    }
}
