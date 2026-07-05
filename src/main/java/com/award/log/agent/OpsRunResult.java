package com.award.log.agent;

import java.util.Map;

/**
 * 运维运行时统一返回结构（编排 Playbook 与巡检单轨共用）。
 */
public record OpsRunResult(String markdown, String traceId, Map<String, Object> report) {
}
