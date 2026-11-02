package com.award.log.agent.team;

import static com.award.log.agent.team.TeamProtocol.*;
import com.award.log.agent.skill.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTeamServiceTest extends TeamFixture {
    AgentTeamService service;TeamToolGateway tools;TeamRoleModel model;ThreadPoolTaskExecutor executor;SkillCatalogLoader skills;
    final Plan plan=new Plan("clean approved fixture",List.of(new Step("CleanTempTool",Map.of("path","fixture","days",0),"approved files recycled")),
            List.of(new Probe("DiskTool",Map.of())),List.of("stop on insufficient evidence"));
    final List<Probe> probes=List.of(new Probe("DiskTool",Map.of()),new Probe("CleanTempTool",Map.of("path","fixture","days",0)));
    @BeforeEach void runtime(){
        var registry=new SkillReleaseRegistry(jdbc,new DataSourceTransactionManager(ds),new SkillReleaseEvaluator());
        skills=new SkillCatalogLoader(registry);tools=mock(TeamToolGateway.class);model=mock(TeamRoleModel.class);
        executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(10);executor.initialize();
        service=new AgentTeamService(store,data,skills,tools,model,executor);
        when(tools.observe(any(),any(),anyString())).thenAnswer(call->{Probe p=call.getArgument(1);return new Evidence(call.getArgument(2),p.tool(),"fixture evidence");});
        when(tools.prepare(any(),any())).thenAnswer(call->new TeamToolGateway.Prepared(call.getArgument(1),List.of(Map.of("success",true)),List.of(Map.of("fixture","ticket")),System.currentTimeMillis()+600000));
        when(tools.resources(any())).thenReturn(Set.of("LOCAL_FILESYSTEM"));
        when(tools.writes(any())).thenAnswer(call->"CleanTempTool".equals(((Probe)call.getArgument(0)).tool()));
        when(tools.execute(any(),any(),anyMap())).thenReturn(Map.of("success",true,"verifyOutcome","VERIFIED","recover",Map.of("handle","fixture-handle")));
        when(model.call(anyString(),any(),any())).thenAnswer(call->{String role=call.getArgument(0);Object output=switch(role){
            case "DIAGNOSIS"->new Diagnosis("fixture diagnosis",List.of("pre-1","pre-2"),false);
            case "PLANNER"->plan;case "VERIFIER"->new Verification("fresh state verified",List.of("post-1"),"VERIFIED");default->throw new AssertionError(role);};
            return new ModelReply(data.encode(output),"fixture-model",1);
        });
    }
    @AfterEach void shutdown(){service.close();executor.shutdown();}
    TeamRunStore.Run start(){return service.start(7,UUID.randomUUID().toString(),"clean only fixture","temp-cleanup",probes);}
    TeamRunStore.Run state(String id,String expected){await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertEquals(expected,store.get(id).state()));return store.get(id);}
    void approve(TeamRunStore.Run run){service.approve(run.id(),run.revision(),run.planHash(),"确认执行","7");}
    @Test void threeRolesHaveIndependentEventsAndWriteWaitsForHumanApproval(){
        var created=start();var waiting=state(created.id(),"WAITING_APPROVAL");
        verify(tools,never()).execute(any(),any(),anyMap());
        assertEquals(2,waiting.modelCalls());assertTrue((boolean)service.view(waiting).get("approvalReady"));
        assertFalse(data.encode(service.view(waiting)).contains("fixture-handle"));
        approve(waiting);var done=state(created.id(),"COMPLETED");assertEquals(3,done.modelCalls());
        verify(model).call(eq("DIAGNOSIS"),any(),any());verify(model).call(eq("PLANNER"),any(),any());verify(model).call(eq("VERIFIER"),any(),any());
        verify(model).call(eq("PLANNER"),argThat(input->input instanceof Map<?,?> m && m.get("verificationTools") instanceof List<?> names && names.contains("DiskTool") && !names.contains("CleanTempTool")),any());
        verify(model).call(eq("VERIFIER"),argThat(input->input instanceof Map<?,?> m && m.containsKey("preEvidence") && m.containsKey("postEvidence")),any());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ops_team_resource_lock",Integer.class));
        assertTrue(store.events(done.id()).stream().anyMatch(e->"WRITE_INTENT".equals(e.get("kind"))));
        assertTrue(store.events(done.id()).stream().anyMatch(e->"TOOL_RECEIPT".equals(e.get("kind"))));
    }
    @Test void failedRoleCanBeTakenOverByHumanPlanThenVerified(){
        when(model.call(eq("DIAGNOSIS"),any(),any())).thenThrow(new IllegalStateException("fixture outage password=\"agent-fixture-secret\""));
        var initial=start();var failed=state(initial.id(),"NEEDS_HUMAN");
        verify(tools,never()).execute(any(),any(),anyMap());
        String events=store.events(initial.id()).toString();
        assertTrue(events.contains("WORKER_FAILURE"));assertTrue(events.contains("ROLE_TOOL_OR_BUDGET_FAILED"));
        assertTrue(events.contains("DIAGNOSING"));assertTrue(events.contains("IllegalStateException"));assertTrue(events.contains("REDACTED"));
        assertFalse(events.contains("agent-fixture-secret"));
        service.edit(failed.id(),failed.revision(),plan,"operator reviewed evidence and supplied plan","7",false);
        var ready=state(initial.id(),"WAITING_APPROVAL");approve(ready);state(initial.id(),"COMPLETED");
        verify(model,times(1)).call(eq("DIAGNOSIS"),any(),any());
        assertTrue(store.events(initial.id()).stream().anyMatch(e->"HUMAN_PLAN".equals(e.get("kind"))));
    }
    @Test void humanPlanEditInvalidatesPriorApprovalAndRequiresNewPreview(){
        var run=state(start().id(),"WAITING_APPROVAL");
        service.edit(run.id(),run.revision(),plan,"reviewed plan","7",false);
        var revised=state(run.id(),"WAITING_APPROVAL");assertTrue(revised.revision()>run.revision());
        assertThrows(IllegalStateException.class,()->service.approve(run.id(),run.revision(),run.planHash(),"确认执行","7"));
        // A stale approval must not consume the new revision's approval binding.
        assertTrue((boolean)service.view(revised).get("approvalReady"));
    }
    @Test void modelCannotOverrideFailedDeterministicWriteVerification(){
        when(tools.execute(any(),any(),anyMap())).thenReturn(Map.of("success",true,"verifyOutcome","EVIDENCE_INCOMPLETE"));
        var ready=state(start().id(),"WAITING_APPROVAL");approve(ready);assertEquals("TOOL_EFFECT_NOT_VERIFIED",state(ready.id(),"NEEDS_HUMAN").error());
        verify(model,never()).call(eq("VERIFIER"),any(),any());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ops_team_resource_lock",Integer.class));
    }
    @Test void inventedEvidenceReferencesStopBeforeAnyWrite(){
        when(model.call(eq("DIAGNOSIS"),any(),any())).thenReturn(new ModelReply(data.encode(new Diagnosis("invented",List.of("unknown"),false)),"fixture",1));
        var initial=start();assertEquals("EVIDENCE_REFERENCE_UNKNOWN",state(initial.id(),"NEEDS_HUMAN").error());verify(tools,never()).execute(any(),any(),anyMap());
    }
    @Test void diagnosisCanExplicitlyRequestMoreEvidence(){
        when(model.call(eq("DIAGNOSIS"),any(),any())).thenReturn(new ModelReply(data.encode(new Diagnosis("need additional logs",List.of("pre-1","pre-2"),true)),"fixture",1));
        var run=state(start().id(),"NEEDS_HUMAN");assertEquals("DIAGNOSIS_NEEDS_EVIDENCE",run.error());
        assertTrue(store.events(run.id()).stream().anyMatch(e->"MODEL_OUTPUT".equals(e.get("kind"))));
        verify(tools,never()).execute(any(),any(),anyMap());
    }
    @Test void operatorCanReplaceEvidenceAfterDiagnosisStopsAndRegeneratePlan(){
        when(model.call(eq("DIAGNOSIS"),any(),any())).thenReturn(
                new ModelReply(data.encode(new Diagnosis("need more evidence",List.of("pre-1","pre-2"),true)),"fixture",1),
                new ModelReply(data.encode(new Diagnosis("sufficient evidence",List.of("pre-1","pre-2"),false)),"fixture",1));
        var stopped=state(start().id(),"NEEDS_HUMAN");
        var replacement=List.of(new Probe("DiskTool",Map.of()),new Probe("CleanTempTool",Map.of("path","updated-fixture","days",7)));
        assertThrows(IllegalStateException.class,()->service.retryEvidence(stopped.id(),stopped.revision()-1,replacement,"supplement evidence","7",false));
        service.retryEvidence(stopped.id(),stopped.revision(),replacement,"supplement evidence","7",false);
        var ready=state(stopped.id(),"WAITING_APPROVAL");
        assertTrue(ready.probesJson().contains("updated-fixture"));assertEquals(3,ready.modelCalls());
        assertTrue(store.events(ready.id()).stream().anyMatch(e->"HUMAN_EVIDENCE_RETRY".equals(e.get("kind"))));
        verify(tools,never()).execute(any(),any(),anyMap());
        approve(ready);state(ready.id(),"COMPLETED");
    }
    @Test void cancellationBeforeApprovalDoesNotWrite(){
        var waiting=state(start().id(),"WAITING_APPROVAL");var cancelled=service.cancel(waiting.id(),"7");
        assertEquals("CANCELLED",cancelled.state());assertThrows(IllegalStateException.class,()->approve(waiting));
        verify(tools,never()).execute(any(),any(),anyMap());
    }
    @Test void restartDoesNotRestoreInMemoryApprovalCapabilities(){
        var waiting=state(start().id(),"WAITING_APPROVAL");service.close();
        service=new AgentTeamService(store,data,skills,tools,model,executor);
        assertFalse((boolean)service.view(waiting).get("approvalReady"));
        assertThrows(IllegalStateException.class,()->approve(waiting));
        service.edit(waiting.id(),waiting.revision(),plan,"preview after restart","7",false);
        var refreshed=state(waiting.id(),"WAITING_APPROVAL");assertTrue((boolean)service.view(refreshed).get("approvalReady"));
    }
    @Test @Timeout(10) void cancelledFutureIsNotTreatedAsProofThatAWriteWorkerExited()throws Exception{
        var entered=new CountDownLatch(1);var release=new java.util.concurrent.atomic.AtomicBoolean();
        when(tools.execute(any(),any(),anyMap())).thenAnswer(call->{entered.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!release.get()&&System.nanoTime()<until){try{Thread.sleep(10);}catch(InterruptedException ignored){/* emulate a provider that ignores interruption */}}
            return Map.of("success",true,"verifyOutcome","VERIFIED","recover","late-fixture-handle");
        });
        try{
            var ready=state(start().id(),"WAITING_APPROVAL");approve(ready);assertTrue(entered.await(3,TimeUnit.SECONDS));
            var cancelled=service.cancel(ready.id(),"7");assertEquals("NEEDS_HUMAN",cancelled.state());
            assertThrows(IllegalStateException.class,()->service.edit(cancelled.id(),cancelled.revision(),plan,"operator review","7",true));
            release.set(true);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertTrue(store.events(ready.id()).toString().contains("late-fixture-handle")));
            assertEquals("NEEDS_HUMAN",store.get(ready.id()).state());verify(model,never()).call(eq("VERIFIER"),any(),any());
        }finally{release.set(true);}
    }
}
