package com.award.log.agent.skill;

import com.award.log.agent.DiagnosisConfidenceGate;
import com.award.log.security.ChatWriteExecutionPolicy;
import com.award.log.security.effect.ConfirmTokenHmac;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.util.*;

/** Deterministic contract/guardrail release fixtures; does not claim business or LLM quality. */
@Component
public class SkillReleaseEvaluator {
    private final JsonNode suite;
    private final String suiteFingerprint;
    public SkillReleaseEvaluator() {
        try(var in=new ClassPathResource("skill-evaluation/contract-v1.json").getInputStream()) {
            byte[] bytes=in.readAllBytes();suite=new ObjectMapper().readTree(bytes);
            suiteFingerprint=ConfirmTokenHmac.hashPreview(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        } catch(java.io.IOException e){throw new IllegalStateException("Skill release fixtures unavailable",e);}
    }
    public Map<String,Object> evaluate(Map<String,SkillCatalogLoader.YamlSkillDefinition> definitions) {
        List<String> failures=new ArrayList<>();Set<String> covered=new HashSet<>();int checks=0;
        var gate=new DiagnosisConfidenceGate();
        for(var fixture:suite.path("skills")) {
            String id=fixture.path("skill").asText();covered.add(id);checks++;
            var def=definitions.get(id);
            if(def==null){failures.add(id+": missing fixture skill");continue;}
            if(!fixture.path("playbook").asText().equals(def.playbook()))failures.add(id+": playbook changed");
            for(var tool:fixture.path("requiredTools"))if(!def.tools().contains(tool.asText()))failures.add(id+": missing tool "+tool.asText());
            if(def.minIndependentSignals()<fixture.path("minimumSignals").asInt())failures.add(id+": insufficient signal floor");
            if(!def.stopConditions().contains("evidence_insufficient"))failures.add(id+": missing insufficient-evidence stop");
            int floor=def.minIndependentSignals();
            List<List<DiagnosisConfidenceGate.Signal>> scenarios=List.of(
                    List.of(new DiagnosisConfidenceGate.Signal("cpu","load",true),new DiagnosisConfidenceGate.Signal("process","top",true)),
                    List.of(new DiagnosisConfidenceGate.Signal("cpu","load",true)),
                    List.of(new DiagnosisConfidenceGate.Signal("cpu","a",true),new DiagnosisConfidenceGate.Signal("cpu","b",true)),List.of());
            for(int i=0;i<scenarios.size();i++) {
                checks++;
                if(gate.evaluate(scenarios.get(i),floor).confirmed()!=(i==0))failures.add(id+": confidence fixture "+i+" failed");
            }
        }
        for(String id:definitions.keySet())if(!covered.contains(id))failures.add(id+": no release fixtures");
        checks+=3;
        if(!ChatWriteExecutionPolicy.resolveRestart(true,true).dryRun())failures.add("explicit restart preview overridden");
        if(!ChatWriteExecutionPolicy.resolveKill(true,true).dryRun())failures.add("explicit kill preview overridden");
        if(!ChatWriteExecutionPolicy.resolveStop(true,true).dryRun())failures.add("explicit stop preview overridden");
        return Map.of("suiteVersion",suite.path("suiteVersion").asText(),"suiteFingerprint",suiteFingerprint,
                "passed",failures.isEmpty(),"checks",checks,"failures",List.copyOf(failures),
                "scope","deterministic contract and guardrail fixtures; no live LLM/business benchmark");
    }
}
