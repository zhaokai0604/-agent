package com.award.log.service.mcp;

import com.award.log.mcp.tools.DiskTool;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAuditTraceService;
import com.award.log.trace.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 调用审计轨迹持久化。
 */
@Service
@RequiredArgsConstructor
public class McpAuditService {

    private final OpsAuditTraceService opsAuditTraceService;
    private final HttpAuditSubject httpAuditSubject;
    private final OpsPathPolicy opsPathPolicy;
    private final DiskTool diskTool;

    public void persistGateReject(String traceId, String userInstruction, String toolName,
                                  GateDecision decision, long startTime) {
        String outcome = McpSecurityService.mapGateCodeToOutcome(decision.getCode());
        String step4 = decision.getAgenticRiskScore() != null
                ? String.format(Locale.ROOT, "风险评分 %.1f/10。安全门代码 %s，拒绝原因: %s",
                decision.getAgenticRiskScore(), decision.getCode(), decision.getMessage())
                : String.format(Locale.ROOT, "安全门代码 %s，拒绝: %s", decision.getCode(), decision.getMessage());
        List<Map<String, Object>> steps = buildCotTrace(userInstruction, toolName, decision, step4);
        opsAuditTraceService.save(traceId, "MCP", userInstruction, RiskLevel.HIGH.name(), outcome, toolName, false,
                decision.getMessage(), steps, System.currentTimeMillis() - startTime,
                httpAuditSubject.currentOperatorId(), opsPathPolicy.getPolicyVersion());
    }

    public void persistNeedConfirm(String traceId, String userInstruction, String toolName,
                                 GateDecision decision, long startTime) {
        double score = decision.getAgenticRiskScore() != null ? decision.getAgenticRiskScore() : 0.0;
        String step4 = String.format(Locale.ROOT,
                "风险评分 %.1f/10，需在前端二次确认后执行。%s",
                score, decision.getMessage() != null ? decision.getMessage() : "");
        List<Map<String, Object>> steps = buildCotTrace(userInstruction, toolName, decision, step4);
        opsAuditTraceService.save(traceId, "MCP", userInstruction, decision.getRiskLevel().name(), "NEED_CONFIRM",
                toolName, false, decision.getMessage(), steps, System.currentTimeMillis() - startTime,
                httpAuditSubject.currentOperatorId(), opsPathPolicy.getPolicyVersion());
    }

    public void persistSuccess(String traceId, String userInstruction, GateDecision decision, String toolName,
                               Map<String, Object> execResponse, long startTime) {
        boolean ok = Boolean.TRUE.equals(execResponse.get("success"));
        String summary = ok ? String.valueOf(execResponse.get("data")) : String.valueOf(execResponse.get("error"));
        String rs = McpSecurityService.formatRiskScoreLine(decision);
        String resultBrief = summary.length() > 600 ? summary.substring(0, 600) + "…" : summary;
        String step4 = String.format(Locale.ROOT,
                "%s 已执行 %s。结果: %s。摘要: %s",
                rs, toolName, ok ? "成功" : "失败", resultBrief);
        List<Map<String, Object>> steps = buildCotTrace(userInstruction, toolName, decision, step4);
        opsAuditTraceService.save(traceId, "MCP", userInstruction, decision.getRiskLevel().name(), "PASS", toolName, ok,
                summary, steps, System.currentTimeMillis() - startTime,
                httpAuditSubject.currentOperatorId(), opsPathPolicy.getPolicyVersion());
    }

    private List<Map<String, Object>> buildCotTrace(String userInstruction, String toolName,
                                                    GateDecision decision, String step4) {
        String s3 = String.format("AI 推理：根据用户指令，将调用工具 %s 完成目标。", toolName);
        if (decision != null && decision.getAgenticRiskDimensions() != null
                && !decision.getAgenticRiskDimensions().isEmpty()) {
            s3 = s3 + " 评分明细: " + decision.getAgenticRiskDimensions();
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(TraceService.cotStep(1, "接收", "接收并解析为: " + userInstruction));
        steps.add(TraceService.cotStep(2, "感知", perceptionNarrative()));
        steps.add(TraceService.cotStep(3, "推理", s3));
        steps.add(TraceService.cotStep(4, "校验", step4));
        return steps;
    }

    private String perceptionNarrative() {
        try {
            return "系统磁盘与分区概览: " + diskTool.checkDiskUsage();
        } catch (Exception e) {
            return "系统状态快照暂不可用: " + e.getMessage();
        }
    }
}
