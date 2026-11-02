package com.award.log.integration;

import static com.award.log.agent.team.TeamProtocol.*;
import com.award.log.agent.team.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Duration;
import java.util.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentTeamApiIntegrationTest extends IntegrationTestBase {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired AgentTeamService service;@Autowired TeamRunStore store;@Autowired TeamDataPolicy data;
    @MockBean TeamToolGateway tools;@MockBean TeamRoleModel model;
    MockHttpSession owner;String id;
    final Plan plan=new Plan("fixture plan",List.of(new Step("CleanTempTool",Map.of("path","fixture"),"fixture recycled")),List.of(new Probe("DiskTool",Map.of())),List.of("stop on failure"));
    @BeforeEach void setup() throws Exception {
        owner=IntegrationTestSupport.registerAndLogin(mvc,"team",jdbc);
        when(tools.observe(any(),any(),anyString())).thenAnswer(call->{Probe p=call.getArgument(1);return new Evidence(call.getArgument(2),p.tool(),"fixture evidence");});
        when(tools.prepare(any(),any())).thenAnswer(call->new TeamToolGateway.Prepared(call.getArgument(1),List.of(Map.of("success",true)),List.of(Map.of()),System.currentTimeMillis()+600000));
        when(tools.resources(any())).thenReturn(Set.of("LOCAL_FILESYSTEM"));
        when(tools.writes(any())).thenAnswer(call->"CleanTempTool".equals(((Probe)call.getArgument(0)).tool()));
        when(tools.execute(any(),any(),anyMap())).thenReturn(Map.of("success",true,"verifyOutcome","VERIFIED"));
        when(model.call(anyString(),any(),any())).thenAnswer(call->{String role=call.getArgument(0);return new ModelReply(data.encode(switch(role){
            case "DIAGNOSIS"->new Diagnosis("fixture",List.of("pre-1","pre-2"),false);case "PLANNER"->plan;case "VERIFIER"->new Verification("fixture verified",List.of("post-1"),"VERIFIED");default->throw new AssertionError(role);}),"fixture-model",1);});
    }
    @AfterEach void cleanup(){if(id!=null){service.cancel(id,"TEST");service.reconcile();}}
    MockHttpServletRequestBuilder postJson(String path,Object payload){return IntegrationTestSupport.withSession(post(path).contentType("application/json").content(data.encode(payload)),owner);}
    String start() throws Exception {
        var result=mvc.perform(postJson("/api/agent-teams",Map.of("requestId",UUID.randomUUID().toString(),"goal","clean fixture only","skillId","temp-cleanup",
                "observations",List.of(new Probe("DiskTool",Map.of()),new Probe("CleanTempTool",Map.of("path","fixture"))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
        id=new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("data").path("runId").asText();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertEquals("WAITING_APPROVAL",store.get(id).state()));return id;
    }
    @Test @Timeout(30) void completeHttpFlowHasIndependentRolesAndNoPublicApprovalTokens()throws Exception {
        start();mvc.perform(get("/api/agent-teams/"+id).session(owner)).andExpect(jsonPath("$.data.approvalReady").value(true));
        var run=store.get(id);
        mvc.perform(postJson("/api/agent-teams/"+id+"/approve",Map.of("revision",run.revision(),"planHash",run.planHash(),"confirmCode","确认执行")))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertEquals("COMPLETED",store.get(id).state()));
        mvc.perform(get("/api/agent-teams/"+id).session(owner)).andExpect(jsonPath("$.data.modelCalls").value(3)).andExpect(jsonPath("$.data.state").value("COMPLETED"));
        verify(model).call(eq("DIAGNOSIS"),any(),any());verify(model).call(eq("PLANNER"),any(),any());verify(model).call(eq("VERIFIER"),any(),any());
    }
    @Test @Timeout(30) void anotherUserCannotReadApproveEditCancelOrListRun()throws Exception {
        start();var other=IntegrationTestSupport.registerAndLogin(mvc,"team-other");
        mvc.perform(get("/api/agent-teams/"+id).session(other)).andExpect(status().isNotFound());
        for(String suffix:List.of("approve","plan","cancel","evidence")){
            Map<String,Object> body="approve".equals(suffix)?Map.of("revision",1,"planHash","fixture","confirmCode","确认执行")
                    :"plan".equals(suffix)?Map.of("revision",1,"plan",plan,"reason","fixture","externalEffectsReviewed",false)
                    :"evidence".equals(suffix)?Map.of("revision",1,"observations",List.of(new Probe("DiskTool",Map.of())),"reason","fixture","externalEffectsReviewed",false):Map.of();
            mvc.perform(IntegrationTestSupport.withSession(post("/api/agent-teams/"+id+"/"+suffix).contentType("application/json").content(data.encode(body)),other)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/agent-teams").session(other)).andExpect(jsonPath("$.data.length()").value(0));
        verify(tools,never()).execute(any(),any(),anyMap());
    }
    @Test @Timeout(30) void humanEditRequiresNewRevisionAndExplicitConfirmation()throws Exception {
        start();var original=store.get(id);
        mvc.perform(postJson("/api/agent-teams/"+id+"/approve",Map.of("revision",original.revision(),"planHash",original.planHash(),"confirmCode","yes"))).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/agent-teams/"+id+"/plan",Map.of("revision",original.revision(),"plan",plan,"reason","operator corrected plan","externalEffectsReviewed",false))).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertEquals("WAITING_APPROVAL",store.get(id).state()));
        mvc.perform(postJson("/api/agent-teams/"+id+"/approve",Map.of("revision",original.revision(),"planHash",original.planHash(),"confirmCode","确认执行"))).andExpect(status().isConflict());
        assertTrue(store.events(id).stream().anyMatch(e->"HUMAN_PLAN".equals(e.get("kind"))));verify(tools,never()).execute(any(),any(),anyMap());
    }
}
