package com.award.log.agent.awm;

import org.springframework.stereotype.Component;

/**
 * 判断轨迹是否适合写入 Workflow Memory（对齐 AWM 的 Leval：仅成功轨迹诱导）。
 */
@Component
public class TrajectoryEvaluator {

    public boolean shouldInduce(OpsExperience experience) {
        if (experience == null || !experience.executionOk()) {
            return false;
        }
        String outcome = experience.securityOutcome();
        if (outcome == null || outcome.isBlank()) {
            return false;
        }
        if (isRejected(outcome)) {
            return false;
        }
        return switch (outcome) {
            case "EXECUTED", "REMEDIATED" -> true;
            case "PREVIEW" -> false;
            case "ERROR", "READ_ONLY_SURFACE" -> false;
            default -> outcome.startsWith("DIAGNOSED") && hasExecuteSteps(experience);
        };
    }

    public boolean isRejected(String securityOutcome) {
        if (securityOutcome == null) {
            return false;
        }
        String u = securityOutcome.toUpperCase();
        return u.startsWith("REJECT") || u.contains("BLOCK");
    }

    private boolean hasExecuteSteps(OpsExperience experience) {
        if (experience.steps() == null) {
            return false;
        }
        return experience.steps().stream()
                .anyMatch(s -> "execute".equalsIgnoreCase(String.valueOf(s.get("phase"))));
    }
}
