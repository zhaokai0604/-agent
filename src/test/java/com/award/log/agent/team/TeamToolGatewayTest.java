package com.award.log.agent.team;

import static com.award.log.agent.team.TeamProtocol.*;
import com.award.log.agent.skill.SkillCatalogLoader.YamlSkillDefinition;
import com.award.log.governance.OpsExecutionPipeline;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.effect.ToolEffectResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TeamToolGatewayTest extends TeamFixture {
    TeamToolGateway gateway;OpsExecutionPipeline pipeline;
    YamlSkillDefinition skill=new YamlSkillDefinition("fixture","fixture","fixture",List.of("DiskTool","CleanTempTool"),List.of("stop"),List.of("no_write_without_pep"),2,"1.0.0",1,"ops-pep/v1");
    Plan plan=new Plan("fixture",List.of(new Step("CleanTempTool",Map.of("path","fixture","days",0),"recycled")),List.of(new Probe("DiskTool",Map.of())),List.of("stop"));
    @BeforeEach void gateway(){pipeline=mock(OpsExecutionPipeline.class);var catalog=mock(McpToolCatalog.class);
        when(catalog.isRegistered(anyString())).thenReturn(true);when(catalog.isHttpAllowed(anyString())).thenReturn(true);
        gateway=new TeamToolGateway(pipeline,catalog,data,new ToolEffectResolver());}
    OpsExecutionPipeline.PipelineResult result(Map<String,Object> body){return new OpsExecutionPipeline.PipelineResult(body,"fixture",0,"CleanTempTool",null,"fp","preview");}
    @Test void modelCannotSelectOutsideSkillOrWriteDuringVerification(){
        gateway.validate(plan,skill);
        assertThrows(IllegalArgumentException.class,()->gateway.validateProbes(List.of(new Probe("UnknownTool",Map.of())),skill));
        var invalid=new Plan("fixture",plan.steps(),List.of(new Probe("CleanTempTool",Map.of("path","fixture"))),List.of("stop"));
        assertThrows(IllegalArgumentException.class,()->gateway.validate(invalid,skill));
        assertThrows(IllegalArgumentException.class,()->gateway.validateProbes(List.of(new Probe("DiskTool",Map.of("command","fixture"))),skill));
    }
    @Test void executablePlanRejectsCredentialsInTextAsWellAsNestedParameters(){
        assertThrows(IllegalArgumentException.class,()->gateway.validate(new Plan("password=fixture-secret",plan.steps(),plan.verification(),plan.stopConditions()),skill));
        assertThrows(IllegalArgumentException.class,()->gateway.validate(new Plan("fixture",List.of(new Step("CleanTempTool",Map.of("path","fixture","note","{\"token\":\"fixture-token\"}"),"recycle")),plan.verification(),plan.stopConditions()),skill));
        assertThrows(IllegalArgumentException.class,()->gateway.validate(new Plan("fixture",List.of(new Step("CleanTempTool",Map.of("path","fixture"),"Bearer fixture-secret-value")),plan.verification(),plan.stopConditions()),skill));
        gateway.validate(plan,skill);
    }
    @Test void writeAlwaysUsesPreviewBoundProposalThenConfirmedPipeline(){
        var run=create("gateway");
        when(pipeline.preview(anyString(),anyMap(),anyString(),anyString())).thenReturn(result(new LinkedHashMap<>(Map.of("success",true,"approvalObjects",Map.of("count",1)))));
        when(pipeline.propose(anyString(),anyMap(),anyString(),anyString(),anyString(),anyString())).thenReturn(result(Map.of("needConfirm",true,"confirmationId","id","capabilityToken","cap","confirmToken","hmac","expiresAtMs",System.currentTimeMillis()+60000)));
        var prepared=gateway.prepare(run,plan);assertEquals("hmac",prepared.tickets().get(0).get("confirmToken"));
        assertFalse(data.encode(prepared.previews()).contains("hmac"));
        when(pipeline.executeConfirmed("id","cap","hmac","确认执行","7",run.id())).thenReturn(result(Map.of("success",true,"verifyOutcome","VERIFIED")));
        assertEquals(true,gateway.execute(run,plan.steps().get(0),prepared.tickets().get(0)).get("success"));
        verify(pipeline,never()).executeDirect(eq("CleanTempTool"),anyMap(),anyString(),anyString());
        assertEquals(Set.of("LOCAL_FILESYSTEM"),gateway.resources(plan));
    }
}
