package com.award.log.service;

import com.award.log.security.HttpAuditSubject;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.RiskLevel;
import com.award.log.service.mcp.McpAuditService;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequiredWriteAuditTest {
    JdbcDataSource source;
    JdbcTemplate jdbc;
    OpsAuditTraceService audit;
    @BeforeEach void setup() {
        source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(source).load().migrate();
        jdbc=new JdbcTemplate(source);audit=new OpsAuditTraceService(jdbc);
    }
    void intent(String id) {
        audit.requireWriteIntent(id,"CleanTempTool","operator","MEDIUM","fixture",
                Map.of("effectFingerprint","fingerprint","parameters",Map.of("path","/tmp/canary","password","fixture-secret")));
    }
    @Test void intentSurvivesAnOuterBusinessRollbackAndIsNotReportedAsSuccess() {
        var outer=new TransactionTemplate(new DataSourceTransactionManager(source));
        outer.executeWithoutResult(status->{
            intent("durable-intent");status.setRollbackOnly();
        });
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ops_audit_trace WHERE trace_id='durable-intent'",Integer.class));
        var row=jdbc.queryForMap("SELECT * FROM ops_audit_trace WHERE trace_id='durable-intent'");
        assertEquals("WRITE_INTENT",row.get("decision"));
        assertEquals(0,((Number)row.get("execution_ok")).intValue());
        assertFalse(String.valueOf(row.get("steps_json")).contains("fixture-secret"));
        assertTrue(audit.findByTraceId("durable-intent").get("steps") instanceof java.util.List<?>);
    }
    @Test void unavailableAuditStorageThrowsRatherThanPretendingToPersist() {
        jdbc.execute("DROP TABLE ops_audit_trace");
        assertThrows(RuntimeException.class,()->intent("unavailable"));
    }
    @Test void missingIdentityCannotCreateAnUnattributedWriteIntent() {
        assertThrows(IllegalStateException.class,()->audit.requireWriteIntent("trace","CleanTempTool","", "MEDIUM","v",Map.of()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ops_audit_trace",Integer.class));
    }
    @Test void pipelineAdapterPersistsTheSuppliedBackgroundOwner() {
        var paths=mock(OpsPathPolicy.class);when(paths.getPolicyVersion()).thenReturn("fixture-policy");
        var adapter=new McpAuditService(audit,new HttpAuditSubject(),paths);
        adapter.persistWriteIntent("owner-intent","ServiceRestartTool",Map.of("serviceName","fixture"),
                "fingerprint","background-owner",GateDecision.allow(RiskLevel.MEDIUM,4,Map.of(),"approved"));
        assertEquals("background-owner",jdbc.queryForObject("SELECT operator_user_id FROM ops_audit_trace WHERE trace_id='owner-intent'",String.class));
    }
}
