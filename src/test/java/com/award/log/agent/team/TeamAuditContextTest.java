package com.award.log.agent.team;

import com.award.log.mcp.dispatch.*;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TeamAuditContextTest {
    @AfterEach void clean(){OpsSecurityContext.clear();}
    @Test void nestedToolInvocationPreservesAuthenticatedOwnerAndRestoresCallerContext(){
        var dispatcher=mock(McpToolDispatcher.class);var execution=new McpExecutionService(dispatcher,new ObjectMapper());
        OpsSecurityContext.open("team","fixture",false,McpToolSurface.FULL,false,false,false,"7","team-session");
        var caller=OpsSecurityContext.get();
        when(dispatcher.dispatch(eq("DiskTool"),anyMap())).thenAnswer(call->{
            assertEquals("7",OpsSecurityContext.get().getRequester());assertEquals("team-session",OpsSecurityContext.get().getSessionId());
            assertEquals("7",new HttpAuditSubject().currentOperatorId());
            return McpToolDispatchResult.ok("{\"success\":true,\"status\":\"SUCCESS\",\"data\":{}}");
        });
        execution.execute("DiskTool",Map.of(),System.currentTimeMillis(),"tool-trace","fixture");
        assertSame(caller,OpsSecurityContext.get());assertEquals("7",new HttpAuditSubject().currentOperatorId());
    }
    @Test void failedInvocationRestoresContextAndAnonymousThreadDoesNotInheritOwner(){
        var dispatcher=mock(McpToolDispatcher.class);var execution=new McpExecutionService(dispatcher,new ObjectMapper());
        when(dispatcher.dispatch(anyString(),anyMap())).thenThrow(new IllegalStateException("fixture failure"));
        assertThrows(IllegalStateException.class,()->execution.execute("DiskTool",Map.of(),0,"trace","fixture"));
        assertNull(OpsSecurityContext.get());assertNull(new HttpAuditSubject().currentOperatorId());
    }
}
