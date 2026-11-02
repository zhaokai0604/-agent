package com.award.log.integration;

import com.award.log.agent.AssistantOrchestrator;
import com.award.log.agent.skill.*;
import com.award.log.security.McpToolSurface;
import com.award.log.security.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillReleaseApiIntegrationTest extends IntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SkillReleaseRegistry registry;
    @Autowired SkillCatalogLoader loader;
    @Autowired AssistantOrchestrator orchestrator;
    MockHttpSession admin;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void login()throws Exception{admin=IntegrationTestSupport.registerAndLogin(mvc,"release-admin",jdbc);}

    @Test void previewNoopPublishAndStaleApprovalHaveExplicitResults()throws Exception{
        var preview=mvc.perform(IntegrationTestSupport.withSession(post("/api/ops/skills/releases/preview"),admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preview.evaluation.passed").value(true)).andReturn();
        var p=json.readTree(preview.getResponse().getContentAsString()).get("preview");
        int before=registry.history(100).size();
        String body=json.writeValueAsString(Map.of("expectedFingerprint",p.get("expectedFingerprint").asText(),"candidateFingerprint",p.get("candidateFingerprint").asText(),"reason","reviewed unchanged deployment"));
        mvc.perform(IntegrationTestSupport.withSession(post("/api/ops/skills/releases/publish").contentType("application/json").content(body),admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.catalog.durable").value(true));
        assertEquals(before,registry.history(100).size());
        mvc.perform(IntegrationTestSupport.withSession(post("/api/ops/skills/releases/publish").contentType("application/json")
                .content(body.replace(p.get("candidateFingerprint").asText(),"stale")),admin)).andExpect(status().isConflict());
    }
    @Test void ordinaryAccountCannotPreviewPublishRollbackOrReadReleaseHistory()throws Exception{
        var user=IntegrationTestSupport.registerAndLogin(mvc,"release-reader");
        mvc.perform(get("/api/ops/skills/yaml").session(user)).andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false));
        for(String action:List.of("preview","publish","rollback")){
            var req=post("/api/ops/skills/releases/"+action).contentType("application/json").content("{}");
            mvc.perform(IntegrationTestSupport.withSession(req,user)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/ops/skills/releases/history").session(user)).andExpect(status().isForbidden());
    }
    @Test void explicitHttpRollbackIsRecordedAndSurvivesLoaderRestart()throws Exception{
        var original=registry.current();var definitions=new TreeMap<String,SkillCatalogLoader.YamlSkillDefinition>();
        loader.all().forEach(d->definitions.put(d.id(),d));var d=definitions.get("cpu-pressure");
        String version="1."+(original.generation()+10)+".0";
        definitions.put(d.id(),new SkillCatalogLoader.YamlSkillDefinition(d.id(),"API rollback fixture",d.playbook(),d.tools(),d.stopConditions(),d.redLines(),d.minIndependentSignals(),version,d.schemaVersion(),d.runtimeContract()));
        var published=registry.publish(original.fingerprint(),definitions,"TEST","prepare rollback fixture");
        String body=json.writeValueAsString(Map.of("expectedFingerprint",published.fingerprint(),"targetFingerprint",original.fingerprint(),"reason","operator reviewed rollback"));
        mvc.perform(IntegrationTestSupport.withSession(post("/api/ops/skills/releases/rollback").contentType("application/json").content(body),admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.catalog.catalogFingerprint").value(original.fingerprint()));
        assertEquals(original.fingerprint(),new SkillCatalogLoader(registry).status().get("catalogFingerprint"));
        assertEquals("ROLLBACK",registry.history(1).get(0).get("action"));
    }
    @Test void actualAssistantEntryPinsTheSameTraceBeforeReadOnlyCompletion(){
        String active=registry.current().fingerprint();
        var result=orchestrator.run("磁盘空间不足，请检查",McpToolSurface.READ_ONLY,RiskLevel.LOW);
        assertEquals(active,jdbc.queryForObject("SELECT release_fingerprint FROM ops_skill_run_pin WHERE run_id=?",String.class,result.traceId()));
        assertEquals("SYSTEM",jdbc.queryForObject("SELECT requester FROM ops_skill_run_pin WHERE run_id=?",String.class,result.traceId()));
    }
}
