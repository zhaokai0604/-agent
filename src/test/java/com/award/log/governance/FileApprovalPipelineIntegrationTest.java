package com.award.log.governance;

import com.award.log.config.*;
import com.award.log.mcp.*;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.security.*;
import com.award.log.security.effect.*;
import com.award.log.service.*;
import com.award.log.service.mcp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 本机真实 canary：计划/人工确认/执行/验证/恢复由项目各组件完成，模型及 HTTP 之外的组件不伪造文件回执。 */
class FileApprovalPipelineIntegrationTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();
    private ExecutorService executor;
    private OpsExecutionPipeline pipeline;
    private OpsRecycleBinService recycle;
    private Path target;
    private MinPrivilegeExecutor min;
    private Map<String,Object> params;
    private McpToolCatalog catalog;
    @BeforeEach void setup() throws Exception {
        target=Files.createDirectories(root.resolve("canary")); Files.writeString(target.resolve("app.log"),"approved original");
        var props=new OpsPepProperties(); recycle=new OpsRecycleBinService(props,root.resolve("recycle").toString());
        executor=Executors.newSingleThreadExecutor();
        var policy=new OpsPathPolicy(new AgentOpsProperties());
        policy.updateEditablePathLists(com.award.log.util.OsRuntime.isWindows(),List.of(root.toString()),List.of(root.toString()),List.of());
        min=mock(MinPrivilegeExecutor.class);
        when(min.executeSafely(anyList(),anyLong())).thenReturn(new AbstractCommandExecutor.CommandResult(true,"OK",null,0,false));
        var tool=new CleanTempTool(executor,json,min,policy,new OpsRemediationProperties(),new SafeDeleteSupport(recycle));
        var execution=mock(McpExecutionService.class);
        org.mockito.stubbing.Answer<Map<String,Object>> invoke=call->{
            Map<String,Object> args=call.getArgument(1);
            String result=tool.cleanTempFiles((String)args.get("path"),0,Boolean.TRUE.equals(args.get("dryRun")),
                    Boolean.TRUE.equals(args.get("confirmDelete")),Boolean.TRUE.equals(args.get("removeDirectory")));
            return json.readValue(result,LinkedHashMap.class);
        };
        when(execution.execute(anyString(),anyMap(),anyLong(),anyString(),anyString())).thenAnswer(invoke);
        when(execution.execute(anyString(),anyMap(),anyLong(),anyString(),anyString(),anyBoolean())).thenAnswer(invoke);
        catalog=mock(McpToolCatalog.class); when(catalog.isRegistered(anyString())).thenReturn(true);
        when(catalog.isHttpAllowed(anyString())).thenReturn(true);
        when(catalog.describePlatformSupport(anyString())).thenReturn(Map.of("available",true));
        var security=mock(McpSecurityService.class);
        when(security.evaluateInitial(anyString(),anyMap(),anyString())).thenReturn(
                McpInvocationSecurityGate.GateDecision.needConfirm(RiskLevel.MEDIUM,"human approval",5,Map.of(),"cleanup"));
        when(security.evaluatePostConfirm(anyString(),anyMap(),anyString())).thenReturn(
                McpInvocationSecurityGate.GateDecision.allow(RiskLevel.MEDIUM,5,Map.of(),"confirmed"));
        when(security.buildAuditInstruction(anyString(),anyString(),anyMap())).thenReturn("canary repair");
        when(security.buildNeedConfirmResponse(anyString(),anyLong(),any(),anyString(),anyMap()))
                .thenAnswer(call->new LinkedHashMap<>(Map.of("success",false,"needConfirm",true)));
        var pending=new McpPendingConfirmationService(); ReflectionTestUtils.setField(pending,"confirmationTtlMs",600_000L);
        pipeline=new OpsExecutionPipeline(catalog,security,execution,mock(McpAuditService.class),pending,
                new ToolEffectResolver(),new SessionRiskBudgetService(60_000,10,100),props,json);
        params=Map.of("path",target.toString(),"days",0,"dryRun",false,"confirmDelete",true,"removeDirectory",true);
    }
    @AfterEach void shutdown() { executor.shutdownNow(); }
    private OpsExecutionPipeline.PipelineResult proposal() throws Exception {
        var preview=pipeline.preview("CleanTempTool",params,"clean canary","owner");
        assertEquals(true,preview.body().get("success"),preview.body().toString());
        assertNotNull(preview.body().get("approvalObjects"));
        var proposal=pipeline.propose("CleanTempTool",params,"clean canary","owner",json.writeValueAsString(preview.body()),"session");
        assertEquals(true,proposal.body().get("needConfirm"),proposal.body().toString()); return proposal;
    }
    private OpsExecutionPipeline.PipelineResult confirm(OpsExecutionPipeline.PipelineResult proposal,String user,String session) {
        var body=proposal.body();return pipeline.executeConfirmed((String)body.get("confirmationId"),(String)body.get("capabilityToken"),
                (String)body.get("confirmToken"),"确认执行",user,session);
    }
    @Test void completeBoundApprovalExecutionVerificationAndRecovery() throws Exception {
        var proposal=proposal(); var result=confirm(proposal,"owner","session");
        assertEquals(true,result.body().get("success"),result.body().toString());
        assertEquals(true,result.body().get("verified")); assertFalse(Files.exists(target));
        Map<?,?> recover=(Map<?,?>)result.body().get("recover");
        String handle=(String)((List<?>)recover.get("recycleHandles")).get(0);
        assertTrue(recycle.restore(handle)); assertEquals("approved original",Files.readString(target.resolve("app.log")));
    }
    @Test void teamGatewayBindsActualPreviewAndRestoresTheApprovedCanary() throws Exception {
        var gateway=new com.award.log.agent.team.TeamToolGateway(pipeline,catalog,
                new com.award.log.agent.team.TeamDataPolicy(json),new ToolEffectResolver());
        var run=new com.award.log.agent.team.TeamRunStore.Run("team-canary",7,"clean canary","temp-cleanup","release","{}","[]",
                "WAITING_APPROVAL",1,"execution",java.time.Instant.now().plusSeconds(60),60000,0,null,null,null,null,null,false);
        var step=new com.award.log.agent.team.TeamProtocol.Step("CleanTempTool",Map.of("path",target.toString(),"days",0,"removeDirectory",true),"recycle canary");
        var plan=new com.award.log.agent.team.TeamProtocol.Plan("clean canary",List.of(step),
                List.of(new com.award.log.agent.team.TeamProtocol.Probe("DiskTool",Map.of())),List.of("stop on mismatch"));
        var prepared=gateway.prepare(run,plan);
        assertTrue(Files.exists(target.resolve("app.log")),"preview and proposal must not write");
        var result=gateway.execute(run,step,prepared.tickets().get(0));
        assertEquals(true,result.get("success"),result.toString());
        assertEquals("VERIFIED",result.get("verifyOutcome"));assertFalse(Files.exists(target));
        Map<?,?> recover=(Map<?,?>)result.get("recover");
        assertTrue(recycle.restore((String)((List<?>)recover.get("recycleHandles")).get(0)));
        assertEquals("approved original",Files.readString(target.resolve("app.log")));
    }
    @Test void teamGatewayRejectsChangedApprovedObjects() throws Exception {
        var gateway=new com.award.log.agent.team.TeamToolGateway(pipeline,catalog,new com.award.log.agent.team.TeamDataPolicy(json),new ToolEffectResolver());
        var run=new com.award.log.agent.team.TeamRunStore.Run("team-canary",7,"clean canary","temp-cleanup","release","{}","[]",
                "WAITING_APPROVAL",1,"execution",java.time.Instant.now().plusSeconds(60),60000,0,null,null,null,null,null,false);
        var step=new com.award.log.agent.team.TeamProtocol.Step("CleanTempTool",Map.of("path",target.toString(),"days",0,"removeDirectory",true),"recycle canary");
        var plan=new com.award.log.agent.team.TeamProtocol.Plan("clean canary",List.of(step),List.of(new com.award.log.agent.team.TeamProtocol.Probe("DiskTool",Map.of())),List.of("stop on mismatch"));
        var prepared=gateway.prepare(run,plan);Files.writeString(target.resolve("app.log"),"changed after approval");
        var result=gateway.execute(run,step,prepared.tickets().get(0));
        assertEquals(false,result.get("success"));assertEquals("APPROVED_OBJECTS_CHANGED",result.get("securityCode"));
        assertEquals("changed after approval",Files.readString(target.resolve("app.log")));assertTrue(recycle.listEntries().isEmpty());
    }
    @Test void teamApprovalCannotCrossOwnerOrSessionAndCannotBeReplayed() throws Exception {
        var gateway=new com.award.log.agent.team.TeamToolGateway(pipeline,catalog,new com.award.log.agent.team.TeamDataPolicy(json),new ToolEffectResolver());
        var expiry=java.time.Instant.now().plusSeconds(60);
        var owner=new com.award.log.agent.team.TeamRunStore.Run("team-owner",7,"clean canary","temp-cleanup","release","{}","[]","WAITING_APPROVAL",1,"execution",expiry,60000,0,null,null,null,null,null,false);
        var otherUser=new com.award.log.agent.team.TeamRunStore.Run("team-owner",8,"clean canary","temp-cleanup","release","{}","[]","WAITING_APPROVAL",1,"execution",expiry,60000,0,null,null,null,null,null,false);
        var otherSession=new com.award.log.agent.team.TeamRunStore.Run("team-other",7,"clean canary","temp-cleanup","release","{}","[]","WAITING_APPROVAL",1,"execution",expiry,60000,0,null,null,null,null,null,false);
        var step=new com.award.log.agent.team.TeamProtocol.Step("CleanTempTool",Map.of("path",target.toString(),"days",0,"removeDirectory",true),"recycle canary");
        var plan=new com.award.log.agent.team.TeamProtocol.Plan("clean canary",List.of(step),List.of(new com.award.log.agent.team.TeamProtocol.Probe("DiskTool",Map.of())),List.of("stop on mismatch"));
        var ticket=gateway.prepare(owner,plan).tickets().get(0);
        assertEquals(false,gateway.execute(otherUser,step,ticket).get("success"));
        assertEquals(false,gateway.execute(otherSession,step,ticket).get("success"));
        assertTrue(Files.exists(target.resolve("app.log")));assertTrue(recycle.listEntries().isEmpty());
        assertEquals(true,gateway.execute(owner,step,ticket).get("success"));
        assertEquals(false,gateway.execute(owner,step,ticket).get("success"));
        assertEquals(1,recycle.listEntries().size());
    }
    @Test void changedContentOrAddedObjectRequiresReapprovalAndLeavesFilesUntouched() throws Exception {
        var proposed=proposal(); Files.writeString(target.resolve("app.log"),"changed content");
        var result=confirm(proposed,"owner","session");
        assertEquals(false,result.body().get("success")); assertEquals(true,result.body().get("confirmationConsumed"));
        assertEquals("APPROVED_OBJECTS_CHANGED",result.body().get("securityCode"));
        assertEquals("changed content",Files.readString(target.resolve("app.log"))); assertTrue(recycle.listEntries().isEmpty());
        assertEquals(false,confirm(proposed,"owner","session").body().get("success"));
        proposed=proposal(); Files.writeString(target.resolve("new.log"),"new");
        assertEquals(false,confirm(proposed,"owner","session").body().get("success")); assertTrue(Files.exists(target.resolve("new.log")));
    }
    @Test void wrongUserAndMissingSessionDoNotBurnTheRightfulTicket() throws Exception {
        var proposed=proposal();
        assertEquals(false,confirm(proposed,"intruder","session").body().get("success"));
        assertEquals(false,confirm(proposed,"owner","").body().get("success"));
        assertTrue(Files.exists(target)); assertEquals(true,confirm(proposed,"owner","session").body().get("success"));
    }
    @Test void frozenFilesRemainExecutableWhenHashingChangesAgeBasedScanResults() throws Exception {
        params=Map.of("path",target.toString(),"days",0,"dryRun",false,"confirmDelete",true,"removeDirectory",false);
        var scans=new java.util.concurrent.atomic.AtomicInteger();
        when(min.executeSafely(anyList(),anyLong())).thenAnswer(call->{
            String cmd=String.join(" ",call.<List<String>>getArgument(0));
            String output="OK";
            if(cmd.contains("Get-ChildItem")) {
                output=json.writeValueAsString(Map.of("files",scans.getAndIncrement()==0?List.of(target.resolve("app.log").toString()):List.of(),"strategy","ACCESS_TIME","bytes",17));
            }
            return new AbstractCommandExecutor.CommandResult(true,output,null,0,false);
        });
        var proposed=proposal(); var result=confirm(proposed,"owner","session");
        assertEquals(true,result.body().get("success"),result.body().toString());
        assertFalse(Files.exists(target.resolve("app.log"))); assertEquals(1,recycle.listEntries().size());
    }

    @Test void previewStaysReadOnlyInsidePreviouslyConfirmedChatContext() throws Exception {
        OpsSecurityContext.openChatAgent("trace","preview",McpToolSurface.FULL,true,false,"owner","session");
        try {
            proposal(); assertTrue(Files.exists(target.resolve("app.log"))); assertTrue(recycle.listEntries().isEmpty());
        } finally { OpsSecurityContext.clear(); }
    }

    @Test void snapshotPoolRefusesExcessPreviewsWithoutTouchingFiles() {
        for(int i=0;i<128;i++) assertEquals(true,pipeline.preview("CleanTempTool",params,"preview","owner").body().get("success"));
        var refused=pipeline.preview("CleanTempTool",params,"preview","owner");
        assertEquals(false,refused.body().get("success"));
        assertTrue(String.valueOf(refused.body().get("error")).contains("上限"));
        assertTrue(Files.exists(target.resolve("app.log")));
    }

    @Test void arbitraryPreviewJsonCannotCreateAnObjectBoundTicket() {
        var result=pipeline.propose("CleanTempTool",params,"cleanup","owner","{\"success\":true}","session");
        assertEquals(false,result.body().get("success")); assertFalse(result.body().containsKey("confirmationId"));
        assertTrue(Files.exists(target));
    }
}
