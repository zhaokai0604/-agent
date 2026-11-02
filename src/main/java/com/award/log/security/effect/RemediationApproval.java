package com.award.log.security.effect;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小审批字段：组织确认预留（不等同于完整 ITSM）。
 */
public record RemediationApproval(
        String operator,
        String confirmer,
        String riskLevel,
        boolean requireSecondPerson,
        String ticketId
) {
    public RemediationApproval {
        operator = blankToEmpty(operator);
        confirmer = blankToEmpty(confirmer);
        riskLevel = blankToEmpty(riskLevel);
        ticketId = blankToEmpty(ticketId);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operator", operator);
        m.put("confirmer", confirmer);
        m.put("riskLevel", riskLevel);
        m.put("requireSecondPerson", requireSecondPerson);
        m.put("ticketId", ticketId);
        return m;
    }

    public static RemediationApproval fromRequesterAndParams(String requester, Map<String, Object> parameters) {
        Map<String, Object> p = parameters == null ? Map.of() : parameters;
        String operator = first(p, "operator", "requester");
        if (operator.isBlank()) {
            operator = requester == null ? "" : requester.trim();
        }
        String confirmer = first(p, "confirmer", "approver", "confirmUser");
        String risk = first(p, "riskLevel", "risk");
        boolean second = bool(p.get("requireSecondPerson")) || bool(p.get("dualControl"));
        String ticket = first(p, "ticketId", "ticket", "workOrderId");
        return new RemediationApproval(operator, confirmer, risk, second, ticket);
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String first(Map<String, Object> p, String... keys) {
        for (String key : keys) {
            Object v = p.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return "";
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(v).trim())
                || "1".equals(String.valueOf(v).trim());
    }
}
