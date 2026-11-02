package com.award.log.agent.team;

import java.util.*;

/** Public, bounded role handoffs. These contain decision summaries, never private reasoning. */
public final class TeamProtocol {
    private TeamProtocol() {}
    public record Probe(String tool, Map<String,Object> parameters) {
        public Probe { parameters=parameters==null?Map.of():Map.copyOf(parameters); }
    }
    public record Evidence(String id, String source, String content) {}
    public record Diagnosis(String summary, List<String> evidenceIds, boolean needsMoreEvidence) {}
    public record Step(String tool, Map<String,Object> parameters, String expectedOutcome) {
        public Step { parameters=parameters==null?Map.of():Map.copyOf(parameters); }
        public Probe probe() { return new Probe(tool,parameters); }
    }
    public record Plan(String summary, List<Step> steps, List<Probe> verification, List<String> stopConditions) {}
    public record Verification(String summary, List<String> evidenceIds, String verdict) {}
    public record ModelReply(String json, String model, long durationMs) {}
}
