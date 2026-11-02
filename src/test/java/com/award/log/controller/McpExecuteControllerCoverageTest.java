package com.award.log.controller;

import com.award.log.config.OpsPepProperties;
import com.award.log.governance.OpsExecutionPipeline;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.RiskLevel;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** HTTP adapter contracts; policy, capabilities and effects are tested in the pipeline suite. */
class McpExecuteControllerCoverageTest {
    McpToolCatalog catalog=mock(McpToolCatalog.class);
    McpSecurityService security=mock(McpSecurityService.class);
    McpExecutionService raw=mock(McpExecutionService.class);
    HttpAuditSubject subject=mock(HttpAuditSubject.class);
    OpsExecutionPipeline pipeline=mock(OpsExecutionPipeline.class);
    McpExecuteController controller;
    OpsExecutionPipeline.PipelineResult result(String tool,Map<String,Object> body) {
        return new OpsExecutionPipeline.PipelineResult(body,"trace",0,tool,null,"","preview");
    }
    @BeforeEach void setup() {
        var legacy=new OpsPepProperties();legacy.setEnabled(false);
        controller=new McpExecuteController(catalog,security,raw,null,null,null,subject,null,null,pipeline,legacy);
        when(subject.currentOperatorId()).thenReturn("tester");
        when(catalog.describePlatformSupport(anyString())).thenReturn(Map.of("available",true));
    }
    @Test void directoryAndNullBodyRemainAvailable() {
        when(catalog.listToolsForApi()).thenReturn(List.of(Map.of("name","DiskTool")));
        assertEquals(2,controller.getMcpTools().get("contractVersion"));
        assertEquals(false,controller.executeTool(null).get("success"));
        assertEquals(400,controller.confirmExecuteTool(null).get("statusCode"));
        verifyNoInteractions(pipeline,raw);
    }
    @Test void disabledLegacyFlagCannotBypassThePipelineOrEscalateAPreview() {
        when(pipeline.preview(anyString(),anyMap(),anyString(),anyString()))
                .thenReturn(result("CleanTempTool",Map.of("success",true)));
        Map<String,Object> params=new HashMap<>(Map.of("path","/tmp/canary","dryRun",true));
        assertEquals(200,controller.executeTool(Map.of("toolName","CleanTempTool","previewOnly",true,"parameters",params)).get("statusCode"));
        verify(pipeline).preview(eq("CleanTempTool"),eq(params),eq(""),eq("tester"));
        assertEquals(true,params.get("dryRun"));
        verify(pipeline,never()).executeDirect(anyString(),anyMap(),anyString(),anyString());
        verifyNoInteractions(raw,security);
    }
    @Test void writeAndReadRequestsBothDelegateToThePipeline() {
        when(pipeline.executeDirect(anyString(),anyMap(),anyString(),anyString()))
                .thenReturn(result("CleanTempTool",Map.of("success",false,"needConfirm",true,"confirmationId","bound")));
        assertEquals(202,controller.executeTool(Map.of("toolName","CleanTempTool","parameters",Map.of("dryRun",false))).get("statusCode"));
        when(pipeline.executeDirect(eq("DiskTool"),anyMap(),anyString(),anyString()))
                .thenReturn(result("DiskTool",Map.of("success",true,"data","observed")));
        assertEquals(200,controller.executeTool(Map.of("toolName","DiskTool")).get("statusCode"));
        verifyNoInteractions(raw,security);
    }
    @Test void confirmationDelegatesOwnerSessionAndBothCapabilities() {
        when(pipeline.executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenReturn(result("ServiceRestartTool",Map.of("success",true)));
        var request=Map.<String,Object>of("confirmationId","bound","capabilityToken","cap","confirmToken","hmac",
                "confirmCode","确认执行","sessionId","session","toolName","forged","parameters",Map.of("path","forged"));
        assertEquals("ServiceRestartTool",controller.confirmExecuteTool(request).get("toolName"));
        verify(pipeline).executeConfirmed("bound","cap","hmac","确认执行","tester","session");
        verifyNoInteractions(raw,security);
    }
    @Test void resultCodesAndWarningsPreserveActionableFailures() {
        when(pipeline.executeDirect(anyString(),anyMap(),anyString(),anyString()))
                .thenReturn(result("Unknown",Map.of("success",false,"error","未找到可执行工具: Unknown")));
        assertEquals(404,controller.executeTool(Map.of("toolName","Unknown")).get("statusCode"));
        when(pipeline.executeDirect(anyString(),anyMap(),anyString(),anyString()))
                .thenReturn(result("DockerTool",Map.of("success",false,"platformSupport",Map.of("available",false))));
        assertEquals(409,controller.executeTool(Map.of("toolName","DockerTool")).get("statusCode"));
        when(pipeline.executeDirect(anyString(),anyMap(),anyString(),anyString()))
                .thenReturn(result("CleanTempTool",Map.of("success",false,"securityCode","AUDIT_UNAVAILABLE","writeMismatchMessage","mismatch")));
        var response=controller.executeTool(Map.of("toolName","CleanTempTool"));
        assertEquals(403,response.get("statusCode"));assertTrue(((List<?>)response.get("warnings")).contains("mismatch"));
    }
    @Test void deferredRequestsCannotPromoteWritesAndReadsStillUsePipeline() {
        assertEquals(400,controller.executeDeferredScheduledTool(" ",null).get("statusCode"));
        assertFalse(Boolean.TRUE.equals(controller.executeDeferredScheduledTool("CleanTempTool",Map.of("dryRun",false)).get("success")));
        verifyNoInteractions(pipeline,raw);
        when(security.evaluateDeferred(anyString(),anyMap())).thenReturn(GateDecision.allow(RiskLevel.LOW,0,Map.of(),"read"));
        when(pipeline.executeDirect(anyString(),anyMap(),anyString(),anyString())).thenReturn(result("DiskTool",Map.of("success",true)));
        assertEquals(200,controller.executeDeferredScheduledTool("DiskTool",Map.of(),"owner").get("statusCode"));
        verify(pipeline).executeDirect(eq("DiskTool"),eq(Map.of()),anyString(),eq("owner"));
    }
}
