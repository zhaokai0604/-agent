package com.award.log.integration;

import com.award.log.agent.team.*;
import com.award.log.agent.team.TeamProtocol.Probe;
import com.award.log.governance.OpsExecutionPipeline;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.time.Duration;
import java.util.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in real model with synthetic tool observations; never touches a host path. */
@EnabledIfSystemProperty(named="thresh.team-live",matches="true")
class AgentTeamLiveModelIT extends IntegrationTestBase {
    @Autowired AgentTeamService teams;
    @Autowired TeamRunStore store;
    @MockBean OpsExecutionPipeline pipeline;
    final java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
    String id;
    OpsExecutionPipeline.PipelineResult result(Map<String,Object> body){return new OpsExecutionPipeline.PipelineResult(body,"fixture-trace",0,"fixture",null,"fixture","fixture-preview");}
    @BeforeEach void fixture(){
        changed.set(false);
        when(pipeline.executeDirect(anyString(),anyMap(),anyString(),anyString())).thenAnswer(call->result(Map.of("success",true,"data",changed.get()?"模拟磁盘复测：容量 100 GB，已用 88 GB（88%），可用 12 GB":"模拟磁盘：容量 100 GB，已用 90 GB（90%），可用 10 GB，超过 80% 告警阈值")));
        when(pipeline.preview(anyString(),anyMap(),anyString(),anyString())).thenReturn(result(Map.of("success",true,"data","模拟预览：C:/Temp/fixture 中 1 个候选文件 app.tmp，大小 2 GB，最后修改于 20 天前，符合 7 天清理条件；未发现受保护文件。当前仅为预览，尚未执行清理")));
        when(pipeline.propose(anyString(),anyMap(),anyString(),anyString(),anyString(),anyString())).thenReturn(result(Map.of("needConfirm",true,"confirmationId","fixture","capabilityToken","fixture","confirmToken","fixture","expiresAtMs",System.currentTimeMillis()+600000)));
        when(pipeline.executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(call->{changed.set(true);return result(Map.of("success",true,"verifyOutcome","VERIFIED","data","模拟回执：app.tmp 已移入回收站，大小 2 GB"));});
    }
    @AfterEach void cleanup(){if(id!=null)teams.cancel(id,"TEST");}
    @Test @Timeout(210) void realModelRolesExchangeEvidenceThroughDurableTeamRuntime(){
        var probes=List.of(new Probe("DiskTool",Map.of()),new Probe("CleanTempTool",Map.of("path","C:/Temp/fixture","days",7)));
        var run=teams.start(7,UUID.randomUUID().toString(),"仅评估模拟测试目录 C:/Temp/fixture 的临时文件清理计划，观察均为模拟数据。","temp-cleanup",probes);id=run.id();
        await().atMost(Duration.ofSeconds(120)).until(()->Set.of("WAITING_APPROVAL","NEEDS_HUMAN").contains(store.get(id).state()));
        var ready=store.get(id);assertEquals("WAITING_APPROVAL",ready.state(),()->"error="+ready.error()+", calls="+ready.modelCalls()+", outputs="+store.events(id).stream().filter(e->"MODEL_OUTPUT".equals(e.get("kind"))).map(e->e.get("payload")).toList());
        teams.approve(id,ready.revision(),ready.planHash(),"确认执行","TEST");
        await().atMost(Duration.ofSeconds(80)).until(()->Set.of("COMPLETED","NEEDS_HUMAN").contains(store.get(id).state()));
        var done=store.get(id);assertEquals("COMPLETED",done.state(),()->"error="+done.error()+", calls="+done.modelCalls()+", outputs="+store.events(id).stream().filter(e->"MODEL_OUTPUT".equals(e.get("kind"))).map(e->e.get("payload")).toList());
        assertEquals(3,done.modelCalls());
        for(String role:List.of("DIAGNOSIS","PLANNER","VERIFIER"))assertTrue(store.events(id).stream().anyMatch(e->role.equals(e.get("actor"))&&"MODEL_OUTPUT".equals(e.get("kind"))));
    }
}
