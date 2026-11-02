package com.award.log.service.impl;

import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.mcp.dispatch.McpToolParamReader;
import com.award.log.governance.OpsExecutionPipeline;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.OpsAuditTraceService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpSecurityService;
import com.award.log.trace.TraceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Runbook 审批流：提交 → 对象预览与批准 → 统一管线执行与验证。
 * <p>
 * 持久化到 {@code ops_runbook_approval}，进程内 cache 加速列表；
 * 批准凭证仅在当前进程有效；数据库领取执行权后才可调用管线，回执丢失不重放。
 */
@Slf4j
@Service
public class RunbookApprovalService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_EXECUTED = "EXECUTED";
    private static final String STATUS_EXECUTION_FAILED = "EXECUTION_FAILED";
    private static final String RESULT_PENDING = "PENDING_APPROVAL";
    private static final String RESULT_REJECTED = "REJECTED";

    private final AtomicLong seq = new AtomicLong(1L);
    private final Map<Long, Map<String, Object>> cache = new ConcurrentHashMap<>();
    private final McpExecutionService mcpExecutionService;
    private final McpSecurityService mcpSecurityService;
    private final McpToolCatalog mcpToolCatalog;
    private final ObjectMapper objectMapper;
    private final OpsAuditTraceService opsAuditTraceService;
    private final OpsPathPolicy opsPathPolicy;
    private final JdbcTemplate jdbcTemplate;
    /** Production runbooks must use the same governed pipeline as HTTP/MCP/AgentTeams. */
    private final OpsExecutionPipeline executionPipeline;
    private record ApprovalTicket(String actor, String confirmationId, String capabilityToken,
                                  String confirmToken, String parameters, long expiresAt) {}
    private final Map<Long, ApprovalTicket> approvalTickets = new ConcurrentHashMap<>();

    /** Administrative review never grants another execution capability. */
    public synchronized Map<String, Object> reconcile(long id, String expectedTraceId, String outcome,
            String evidence, String confirmCode, boolean externalEffectsReviewed, Object reviewer) {
        if (!"核对结案".equals(confirmCode) || !externalEffectsReviewed) {
            throw new IllegalArgumentException("必须核对外部影响并输入核对结案");
        }
        if (!java.util.Set.of("EFFECT_CONFIRMED", "RECOVERED", "NO_EFFECT", "UNKNOWN").contains(outcome == null ? "" : outcome)) {
            throw new IllegalArgumentException("Unsupported manual review outcome");
        }
        if (evidence == null || evidence.isBlank()) throw new IllegalArgumentException("核对证据不能为空");
        var privacy = new com.award.log.agent.team.TeamDataPolicy(objectMapper);
        privacy.bounded(evidence, 8000);
        if ("unknown".equals(asActor(reviewer)) || asActor(reviewer).isBlank()) {
            throw new IllegalArgumentException("Reviewer identity is required");
        }
        Map<String, Object> item = loadForMutation(id);
        ensureStatus(item, "EXECUTING", "Only unresolved executions can be manually closed");
        if (expectedTraceId == null || !expectedTraceId.equals(item.get("traceId"))) {
            throw new IllegalStateException("Execution trace changed; reload before reviewing");
        }
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("reviewer", asActor(reviewer));
        review.put("reviewedAt", System.currentTimeMillis());
        review.put("executionTraceId", expectedTraceId);
        review.put("outcome", outcome);
        review.put("evidence", privacy.redact(evidence));
        review.put("source", "HUMAN_REVIEW");
        review.put("automaticVerification", false);
        Map<String, Object> result = new LinkedHashMap<>();
        if (item.get("mcpResult") != null) result.put("previousResult", item.get("mcpResult"));
        result.put("manualReview", review);
        String json = privacy.safe(result);
        // Evidence and the terminal state commit in the same SQL statement.
        int changed = jdbcTemplate.update("UPDATE ops_runbook_approval SET status='MANUALLY_CLOSED', "
                        + "result_code='MANUAL_REVIEW',mcp_result_json=?,execution_message=? "
                        + "WHERE id=? AND status='EXECUTING' AND trace_id=?",
                json, "人工核对结案；该记录不授予重放权限，也不证明原工作线程已停止", id, expectedTraceId);
        if (changed != 1) throw new IllegalStateException("Runbook state changed; reload before reviewing");
        approvalTickets.remove(id);
        var closed = loadForMutation(id);
        cache.put(id, mutableCopy(closed));
        return snapshot(closed);
    }

    public RunbookApprovalService(
            McpExecutionService mcpExecutionService,
            McpSecurityService mcpSecurityService,
            McpToolCatalog mcpToolCatalog,
            ObjectMapper objectMapper,
            OpsAuditTraceService opsAuditTraceService,
            OpsPathPolicy opsPathPolicy,
            JdbcTemplate jdbcTemplate) {
        this(mcpExecutionService, mcpSecurityService, mcpToolCatalog, objectMapper,
                opsAuditTraceService, opsPathPolicy, jdbcTemplate, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RunbookApprovalService(
            McpExecutionService mcpExecutionService,
            McpSecurityService mcpSecurityService,
            McpToolCatalog mcpToolCatalog,
            ObjectMapper objectMapper,
            OpsAuditTraceService opsAuditTraceService,
            OpsPathPolicy opsPathPolicy,
            JdbcTemplate jdbcTemplate,
            OpsExecutionPipeline executionPipeline) {
        this.mcpExecutionService = mcpExecutionService;
        this.mcpSecurityService = mcpSecurityService;
        this.mcpToolCatalog = mcpToolCatalog;
        this.objectMapper = objectMapper;
        this.opsAuditTraceService = opsAuditTraceService;
        this.opsPathPolicy = opsPathPolicy;
        this.jdbcTemplate = jdbcTemplate;
        this.executionPipeline = executionPipeline;
    }

    @PostConstruct
    void init() {
        initializeSequence();
        warmCache();
    }

    /**
     * 提交待审批 Runbook。须提供已注册 MCP 工具，或 action/command 可解析出工具名。
     */
    public Map<String, Object> submit(
            String title,
            String action,
            String command,
            String toolName,
            Map<String, Object> parameters,
            Object requester) {
        String safeTitle = blankToDefault(title, "Runbook");
        String safeAction = blankToNull(action);
        String safeCommand = blankToNull(command);
        String resolvedTool = blankToNull(toolName);
        if (resolvedTool == null && safeAction != null && safeAction.endsWith("Tool")) {
            resolvedTool = safeAction;
        }
        Map<String, Object> resolvedParams = parameters != null
                ? new LinkedHashMap<>(parameters)
                : parseCommandParameters(safeCommand);

        if (resolvedTool == null && safeAction == null && safeCommand == null) {
            throw new IllegalArgumentException("Runbook requires toolName+parameters or action/command");
        }
        if (resolvedTool != null && !mcpToolCatalog.isRegistered(resolvedTool)) {
            throw new IllegalArgumentException("Unknown MCP tool: " + resolvedTool);
        }

        long id = seq.getAndIncrement();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("title", safeTitle);
        item.put("action", safeAction == null ? "" : safeAction);
        item.put("command", safeCommand == null ? "" : safeCommand);
        item.put("toolName", resolvedTool == null ? "" : resolvedTool);
        item.put("parameters", resolvedParams);
        item.put("requester", asActor(requester));
        item.put("status", STATUS_PENDING);
        item.put("result", RESULT_PENDING);
        item.put("executionImplemented", false);
        item.put("createdAt", System.currentTimeMillis());

        persistNew(item);
        cache.put(id, mutableCopy(item));
        return snapshot(item);
    }

    /** 未执行的工单可批准或重新批准；固定当前对象预览，不立刻执行。 */
    public synchronized Map<String, Object> approve(long id, Object approver) {
        Map<String, Object> item = loadForMutation(id);
        String status = String.valueOf(item.get("status"));
        if (!STATUS_PENDING.equals(status) && !STATUS_APPROVED.equals(status)) {
            throw new IllegalStateException("Only pending or unexecuted approved runbooks can be approved");
        }
        if (executionPipeline == null) throw new IllegalStateException("Governed execution pipeline is required");
        String actor = asActor(approver);
        String tool = String.valueOf(item.getOrDefault("toolName", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) item.get("parameters"));
        McpToolParamReader.forceConfirmedWriteToolParams(tool, params);
        String message = "[RUNBOOK] " + item.get("title");
        var preview = executionPipeline.preview(tool, params, message, actor);
        if (preview.body() == null || !Boolean.TRUE.equals(preview.body().get("success"))) {
            throw new IllegalStateException("Runbook preview failed; approval was not granted");
        }
        var proposal = executionPipeline.propose(tool, params, message, actor, preview.dryRunPreviewJson());
        if (proposal.body() == null || !Boolean.TRUE.equals(proposal.body().get("needConfirm"))) {
            throw new IllegalStateException("Runbook approval ticket was not issued");
        }
        var body = proposal.body();
        ApprovalTicket ticket = new ApprovalTicket(actor, String.valueOf(body.get("confirmationId")),
                String.valueOf(body.get("capabilityToken")), String.valueOf(body.get("confirmToken")),
                toJson(params), ((Number) body.get("expiresAtMs")).longValue());
        item.put("status", STATUS_APPROVED);
        item.put("approver", asActor(approver));
        item.put("approvedAt", System.currentTimeMillis());
        item.put("traceId", ticket.confirmationId());
        persistExisting(item);
        cache.put(id, mutableCopy(item));
        approvalTickets.put(id, ticket);
        return snapshot(item);
    }

    /** PENDING 或 APPROVED 可拒绝。 */
    public synchronized Map<String, Object> reject(long id, Object approver, String reason) {
        Map<String, Object> item = loadForMutation(id);
        String status = String.valueOf(item.get("status"));
        if (!STATUS_PENDING.equals(status) && !STATUS_APPROVED.equals(status)) {
            throw new IllegalStateException("Only pending or approved runbooks can be rejected");
        }
        item.put("status", STATUS_REJECTED);
        item.put("approver", asActor(approver));
        item.put("reason", reason == null ? "" : reason.trim());
        item.put("result", RESULT_REJECTED);
        item.put("approvedAt", System.currentTimeMillis());
        persistExisting(item);
        cache.put(id, mutableCopy(item));
        approvalTickets.remove(id);
        return snapshot(item);
    }

    /**
     * 仅 APPROVED 可执行：领取执行权 → 安全门 → 消费批准时固定的管线凭证。
     * 安全门 BLOCK 时记 EXECUTION_FAILED，不真正调工具。
     */
    public synchronized Map<String, Object> execute(long id, Object operator) {
        Map<String, Object> item = loadForMutation(id);
        ensureStatus(item, STATUS_APPROVED, "Only approved runbooks can be executed");

        String toolName = String.valueOf(item.getOrDefault("toolName", "")).trim();
        if (toolName.isEmpty()) {
            throw new IllegalStateException("Runbook is missing toolName");
        }
        if (!mcpToolCatalog.isHttpAllowed(toolName)) {
            throw new IllegalStateException("Tool is not allowed for HTTP execution: " + toolName);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = item.get("parameters") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();

        String userMessage = "[RUNBOOK] " + item.get("title");
        McpToolParamReader.forceConfirmedWriteToolParams(toolName, parameters);
        if (executionPipeline == null) throw new IllegalStateException("Governed execution pipeline is required");
        ApprovalTicket ticket = approvalTickets.get(id);
        if (ticket == null || ticket.expiresAt() <= System.currentTimeMillis()
                || !ticket.actor().equals(asActor(operator)) || !ticket.parameters().equals(toJson(parameters))) {
            throw new IllegalStateException("Approval expired, belongs to another operator, or changed; approve again");
        }
        // Persist ownership before any external side effect.  A lost receipt
        // leaves EXECUTING for manual reconciliation and can never be replayed.
        int claimed = jdbcTemplate.update(
                "UPDATE ops_runbook_approval SET status='EXECUTING', operator=?, executed_at=? WHERE id=? AND status='APPROVED' AND trace_id=?",
                asActor(operator), new Timestamp(System.currentTimeMillis()), id, ticket.confirmationId());
        if (claimed != 1) throw new IllegalStateException("Runbook is already executing or its approval changed");
        approvalTickets.remove(id);
        item.put("status", "EXECUTING");
        cache.put(id, mutableCopy(item));
        GateDecision decision = mcpSecurityService.evaluatePostConfirm(toolName, parameters, userMessage);

        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        item.put("operator", asActor(operator));
        item.put("executedAt", startTime);
        item.put("traceId", traceId);

        if (decision.getType() == GateDecision.Type.BLOCK) {
            item.put("status", STATUS_EXECUTION_FAILED);
            item.put("result", decision.getCode());
            item.put("executionImplemented", true);
            item.put("executionMessage", decision.getMessage());
            item.put("mcpSuccess", false);
            persistAudit(id, traceId, userMessage, toolName, parameters, decision, null, false, startTime, asActor(operator));
            persistExisting(item);
            cache.put(id, mutableCopy(item));
            return snapshot(item);
        }

        {
            // Consume the exact ticket created at approval; never generate a
            // fresh preview here, which could silently broaden approved objects.
            OpsExecutionPipeline.PipelineResult governed = executionPipeline.executeConfirmed(
                    ticket.confirmationId(), ticket.capabilityToken(), ticket.confirmToken(), "确认执行", asActor(operator));
            Map<String, Object> execResponse = governed.body() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(governed.body());
            boolean success = Boolean.TRUE.equals(execResponse.get("success"));
            item.put("status", success ? STATUS_EXECUTED : STATUS_EXECUTION_FAILED);
            item.put("mcpResult", execResponse);
            item.put("mcpSuccess", success);
            item.put("executionImplemented", true);
            item.put("traceId", governed.traceId());
            item.put("executedAt", startTime);
            item.put("executionMessage", success
                    ? "Executed through governed OpsExecutionPipeline: " + toolName
                    : String.valueOf(execResponse.getOrDefault("error", "EXECUTION_FAILED")));
            item.put("result", success
                    ? "EXECUTED"
                    : String.valueOf(execResponse.getOrDefault("error", "EXECUTION_FAILED")));
            if (execResponse.get("writeMismatch") != null) {
                item.put("writeMismatch", execResponse.get("writeMismatch"));
            }
            if (WriteToolResultSupport.requestedRealWrite(parameters)) {
                String mode = WriteToolResultSupport.extractMode(
                        execResponse.get("data") != null ? String.valueOf(execResponse.get("data")) : "");
                if (!mode.isBlank()) {
                    item.put("writeMode", mode);
                }
            }
            persistAudit(id, governed.traceId(), userMessage, toolName, parameters,
                    decision, execResponse, success, startTime, asActor(operator));
            persistExisting(item);
            cache.put(id, mutableCopy(item));
            return snapshot(item);
        }

    }

    /** 列表：管理员看全部，否则按 requester 过滤；DB 不可用时回退内存 cache。 */
    public List<Map<String, Object>> list(String requester, boolean admin) {
        List<Map<String, Object>> rows = queryList(requester, admin);
        if (rows == null) {
            return cache.values().stream()
                    .filter(item -> admin || String.valueOf(item.get("requester")).equals(requester))
                    .sorted(Comparator.comparingLong(this::createdAt).reversed())
                    .map(this::snapshot)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        List<Map<String, Object>> items = rows.stream()
                .map(this::fromDbRow)
                .sorted(Comparator.comparingLong(this::createdAt).reversed())
                .collect(Collectors.toCollection(ArrayList::new));
        for (Map<String, Object> item : items) {
            cache.put(toLong(item.get("id")), mutableCopy(item));
        }
        return items.stream().map(this::snapshot).collect(Collectors.toCollection(ArrayList::new));
    }


    private void initializeSequence() {
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM ops_runbook_approval",
                Long.class);
        seq.set((maxId == null ? 0L : maxId) + 1L);
    }

    private void warmCache() {
        List<Map<String, Object>> rows = queryAllRows();
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = fromDbRow(row);
            cache.put(toLong(item.get("id")), mutableCopy(item));
        }
    }

    private void persistNew(Map<String, Object> item) {
        int updated = jdbcTemplate.update(
                """
                INSERT INTO ops_runbook_approval (
                  id, title, action_name, command_text, tool_name, parameters_json,
                  requester, status, result_code, execution_implemented,
                  approver, reason, operator, trace_id, mcp_result_json, mcp_success,
                  execution_message, write_mismatch, write_mode,
                  created_at, approved_at, executed_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                item.get("id"),
                item.get("title"),
                emptyToNull(String.valueOf(item.getOrDefault("action", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("command", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("toolName", ""))),
                toJson(item.get("parameters")),
                String.valueOf(item.get("requester")),
                item.get("status"),
                emptyToNull(String.valueOf(item.getOrDefault("result", ""))),
                boolToDb(item.get("executionImplemented")),
                emptyToNull(String.valueOf(item.getOrDefault("approver", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("reason", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("operator", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("traceId", ""))),
                toJson(item.get("mcpResult")),
                nullableBoolToDb(item.get("mcpSuccess")),
                emptyToNull(String.valueOf(item.getOrDefault("executionMessage", ""))),
                nullableBoolToDb(item.get("writeMismatch")),
                emptyToNull(String.valueOf(item.getOrDefault("writeMode", ""))),
                toTimestamp(item.get("createdAt")),
                toTimestamp(item.get("approvedAt")),
                toTimestamp(item.get("executedAt")));
        if (updated <= 0) {
            throw new IllegalStateException("Failed to persist runbook approval");
        }
    }

    private void persistExisting(Map<String, Object> item) {
        String allowed = STATUS_APPROVED.equals(item.get("status")) || STATUS_REJECTED.equals(item.get("status"))
                ? "status IN ('PENDING','APPROVED')" : "status='EXECUTING'";
        int updated = jdbcTemplate.update(
                """
                UPDATE ops_runbook_approval
                SET title = ?,
                    action_name = ?,
                    command_text = ?,
                    tool_name = ?,
                    parameters_json = ?,
                    requester = ?,
                    status = ?,
                    result_code = ?,
                    execution_implemented = ?,
                    approver = ?,
                    reason = ?,
                    operator = ?,
                    trace_id = ?,
                    mcp_result_json = ?,
                    mcp_success = ?,
                    execution_message = ?,
                    write_mismatch = ?,
                    write_mode = ?,
                    approved_at = ?,
                    executed_at = ?
                WHERE id = ? AND %s
                """.formatted(allowed),
                item.get("title"),
                emptyToNull(String.valueOf(item.getOrDefault("action", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("command", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("toolName", ""))),
                toJson(item.get("parameters")),
                String.valueOf(item.get("requester")),
                item.get("status"),
                emptyToNull(String.valueOf(item.getOrDefault("result", ""))),
                boolToDb(item.get("executionImplemented")),
                emptyToNull(String.valueOf(item.getOrDefault("approver", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("reason", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("operator", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("traceId", ""))),
                toJson(item.get("mcpResult")),
                nullableBoolToDb(item.get("mcpSuccess")),
                emptyToNull(String.valueOf(item.getOrDefault("executionMessage", ""))),
                nullableBoolToDb(item.get("writeMismatch")),
                emptyToNull(String.valueOf(item.getOrDefault("writeMode", ""))),
                toTimestamp(item.get("approvedAt")),
                toTimestamp(item.get("executedAt")),
                item.get("id"));
        if (updated <= 0) {
            if (STATUS_EXECUTED.equals(item.get("status")) || STATUS_EXECUTION_FAILED.equals(item.get("status"))) {
                var rows = jdbcTemplate.queryForList("SELECT * FROM ops_runbook_approval WHERE id = ?", item.get("id"));
                if (!rows.isEmpty() && "MANUALLY_CLOSED".equals(rows.get(0).get("status"))) {
                    Map<String, Object> row = rows.get(0);
                    String oldJson = String.valueOf(row.get("mcp_result_json"));
                    Map<String, Object> result = parseJsonMap(oldJson);
                    result.put("lateReceipt", Map.of("traceId", String.valueOf(item.get("traceId")),
                            "receivedAt", System.currentTimeMillis(), "status", item.get("status"),
                            "result", item.getOrDefault("mcpResult", Map.of())));
                    String json = new com.award.log.agent.team.TeamDataPolicy(objectMapper).safe(result);
                    int appended = jdbcTemplate.update("UPDATE ops_runbook_approval SET mcp_result_json=? "
                                    + "WHERE id=? AND status='MANUALLY_CLOSED' AND mcp_result_json=?",
                            json, item.get("id"), oldJson);
                    if (appended != 1) throw new IllegalStateException("Late receipt conflict; inspect pipeline audit");
                    var closed = loadForMutation(toLong(item.get("id")));
                    item.clear(); item.putAll(closed);
                    return;
                }
            }
            throw new NoSuchElementException("Runbook request not found");
        }
    }

    private void persistAudit(
            long runbookId,
            String traceId,
            String userMessage,
            String toolName,
            Map<String, Object> parameters,
            GateDecision decision,
            Map<String, Object> execResponse,
            boolean success,
            long startTime,
            String operator) {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(TraceService.cotStep(1, "receive", "Receive runbook execution request, runbookId=" + runbookId));
        steps.add(TraceService.cotStep(2, "confirm", "Approval passed, operator=" + operator));
        steps.add(TraceService.cotStep(3, "reason", "Invoke tool " + toolName + " with params: " + truncate(String.valueOf(parameters), 1200)));
        String executionDetail = decision.getType() == GateDecision.Type.BLOCK
                ? "Blocked by security gate before execution: " + decision.getMessage()
                : "Execution result: " + truncate(String.valueOf(execResponse), 1200);
        steps.add(TraceService.cotStep(4, "execute", executionDetail));
        steps.add(TraceService.step("runbook", "title=" + userMessage + ", tool=" + toolName));

        String securityOutcome;
        String summary;
        boolean executionOk;
        if (decision.getType() == GateDecision.Type.BLOCK) {
            securityOutcome = McpSecurityService.mapGateCodeToOutcome(decision.getCode());
            summary = decision.getMessage();
            executionOk = false;
        } else {
            securityOutcome = success ? "PASS" : "EXECUTION_FAILED";
            summary = success
                    ? String.valueOf(execResponse.get("data"))
                    : String.valueOf(execResponse.getOrDefault("error", "EXECUTION_FAILED"));
            executionOk = success;
        }

        opsAuditTraceService.save(
                traceId,
                "RUNBOOK",
                userMessage,
                decision.getRiskLevel().name(),
                securityOutcome,
                toolName,
                executionOk,
                summary,
                steps,
                System.currentTimeMillis() - startTime,
                operator,
                opsPathPolicy.getPolicyVersion());
    }

    private Map<String, Object> parseCommandParameters(String command) {
        if (command == null || command.isBlank()) {
            return new LinkedHashMap<>();
        }
        String trimmed = command.trim();
        if (!trimmed.startsWith("{")) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("command must be a valid JSON object: " + e.getMessage());
        }
    }

    private void ensureStatus(Map<String, Object> item, String expected, String message) {
        String actual = String.valueOf(item.get("status"));
        if (!expected.equals(actual)) {
            throw new IllegalStateException(message);
        }
    }

    private Map<String, Object> loadForMutation(long id) {
        // Never authorize writes from the process-local listing cache.
        var rows = jdbcTemplate.queryForList("SELECT * FROM ops_runbook_approval WHERE id = ?", id);
        if (rows.isEmpty()) throw new NoSuchElementException("Runbook request not found");
        return fromDbRow(rows.get(0));
    }

    private Map<String, Object> getById(long id) {
        Map<String, Object> cached = cache.get(id);
        if (cached != null) {
            return snapshot(cached);
        }
        Map<String, Object> loaded = loadOneFromDb(id);
        if (loaded != null) {
            cache.put(id, mutableCopy(loaded));
            return loaded;
        }
        throw new NoSuchElementException("Runbook request not found");
    }

    private List<Map<String, Object>> queryList(String requester, boolean admin) {
        try {
            if (admin) {
                return jdbcTemplate.queryForList(
                        "SELECT * FROM ops_runbook_approval ORDER BY created_at DESC, id DESC");
            }
            return jdbcTemplate.queryForList(
                    "SELECT * FROM ops_runbook_approval WHERE requester = ? ORDER BY created_at DESC, id DESC",
                    requester);
        } catch (Exception e) {
            log.warn("failed to list runbook approvals: {}", e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> queryAllRows() {
        try {
            return jdbcTemplate.queryForList("SELECT * FROM ops_runbook_approval ORDER BY id ASC");
        } catch (Exception e) {
            log.warn("failed to warm runbook cache: {}", e.getMessage());
            return null;
        }
    }

    private long createdAt(Map<String, Object> item) {
        Object value = item.get("createdAt");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, Object> snapshot(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private Map<String, Object> mutableCopy(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private Map<String, Object> loadOneFromDb(long id) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM ops_runbook_approval WHERE id = ?",
                    id);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return fromDbRow(rows.get(0));
        } catch (Exception e) {
            log.warn("failed to query runbook approval {}: {}", id, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> fromDbRow(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", toLong(row.get("id")));
        item.put("title", String.valueOf(row.getOrDefault("title", "Runbook")));
        item.put("action", safeDbString(row.get("action_name")));
        item.put("command", safeDbString(row.get("command_text")));
        item.put("toolName", safeDbString(row.get("tool_name")));
        item.put("parameters", parseJsonMap(row.get("parameters_json")));
        item.put("requester", String.valueOf(row.getOrDefault("requester", "unknown")));
        item.put("status", String.valueOf(row.getOrDefault("status", STATUS_PENDING)));
        item.put("result", String.valueOf(row.getOrDefault("result_code", RESULT_PENDING)));
        item.put("executionImplemented", toBoolean(row.get("execution_implemented")));
        putIfNotBlank(item, "approver", row.get("approver"));
        putIfNotBlank(item, "reason", row.get("reason"));
        putIfNotBlank(item, "operator", row.get("operator"));
        putIfNotBlank(item, "traceId", row.get("trace_id"));
        Map<String, Object> mcpResult = parseJsonMap(row.get("mcp_result_json"));
        if (!mcpResult.isEmpty()) {
            item.put("mcpResult", mcpResult);
        }
        if (row.get("mcp_success") != null) {
            item.put("mcpSuccess", toBoolean(row.get("mcp_success")));
        }
        putIfNotBlank(item, "executionMessage", row.get("execution_message"));
        if (row.get("write_mismatch") != null) {
            item.put("writeMismatch", toBoolean(row.get("write_mismatch")));
        }
        putIfNotBlank(item, "writeMode", row.get("write_mode"));
        putIfTimestamp(item, "createdAt", row.get("created_at"));
        putIfTimestamp(item, "approvedAt", row.get("approved_at"));
        putIfTimestamp(item, "executedAt", row.get("executed_at"));
        return item;
    }

    private static String asActor(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToDefault(String value, String defaultValue) {
        String trimmed = blankToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize runbook payload", e);
        }
    }

    private Map<String, Object> parseJsonMap(Object raw) {
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        String json = String.valueOf(raw).trim();
        if (json.isEmpty() || "null".equalsIgnoreCase(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("failed to parse runbook json payload: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static Timestamp toTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value instanceof Number number) {
            return new Timestamp(number.longValue());
        }
        return null;
    }

    private static Long nullableBoolToDb(Object value) {
        if (value == null) {
            return null;
        }
        return toBoolean(value) ? 1L : 0L;
    }

    private static int boolToDb(Object value) {
        return toBoolean(value) ? 1 : 0;
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        return value.isBlank() ? null : value;
    }

    private static String safeDbString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void putIfNotBlank(Map<String, Object> item, String key, Object raw) {
        if (raw == null) {
            return;
        }
        String value = String.valueOf(raw);
        if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
            item.put(key, value);
        }
    }

    private static void putIfTimestamp(Map<String, Object> item, String key, Object raw) {
        if (raw instanceof Timestamp timestamp) {
            item.put(key, timestamp.getTime());
        }
    }
}
