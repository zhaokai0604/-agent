package com.award.log.controller;

import com.award.log.security.AuthInterceptor;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.impl.RunbookApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class RunbookReconciliationApiTest {
    @Test void onlyAdminMayCloseAndIdentityComesFromSession() throws Exception {
        var service=mock(RunbookApprovalService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new RunbookApprovalController(service,new RequestUserResolver())).build();
        String body="""
                {"expectedTraceId":"trace","outcome":"UNKNOWN","evidence":"external state checked",
                 "confirmCode":"核对结案","externalEffectsReviewed":true,"reviewer":"spoofed"}
                """;
        mvc.perform(post("/api/runbook/1/reconcile").sessionAttr(AuthInterceptor.SESSION_USER_ID,7)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE,2).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
        when(service.reconcile(1,"trace","UNKNOWN","external state checked","核对结案",true,"7"))
                .thenReturn(Map.of("status","MANUALLY_CLOSED"));
        mvc.perform(post("/api/runbook/1/reconcile").sessionAttr(AuthInterceptor.SESSION_USER_ID,7)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE,1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.status").value("MANUALLY_CLOSED"));
        verify(service).reconcile(1,"trace","UNKNOWN","external state checked","核对结案",true,"7");
    }
    @Test void invalidInputAndStateConflictsHaveDistinctResults() throws Exception {
        var service=mock(RunbookApprovalService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new RunbookApprovalController(service,new RequestUserResolver())).build();
        when(service.reconcile(eq(1L),any(),any(),any(),any(),eq(false),eq("7")))
                .thenThrow(new IllegalArgumentException("missing confirmation"));
        when(service.reconcile(eq(2L),any(),any(),any(),any(),eq(false),eq("7")))
                .thenThrow(new IllegalStateException("state changed"));
        mvc.perform(post("/api/runbook/1/reconcile").sessionAttr(AuthInterceptor.SESSION_USER_ID,7)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE,1).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/runbook/2/reconcile").sessionAttr(AuthInterceptor.SESSION_USER_ID,7)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE,1).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(409));
    }
}
