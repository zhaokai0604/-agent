package com.award.log.agent.awm;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowMemoryServiceCoverageTest {

    @Test
    void reloadAndUpsertHandleValidInvalidAndDisabledRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkflowMemoryService service = new WorkflowMemoryService(jdbc);
        ReflectionTestUtils.setField(service, "enabled", true);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                row("wf-valid", "disk", "[{\"toolName\":\"DiskTool\",\"argsTemplate\":{\"path\":null}}]", 2, 1),
                row("wf-bad", "disk", "not-json", 0, 1)));

        service.reloadCache();
        assertEquals(1, service.countEnabled());
        assertEquals("wf-valid", service.listByDomain("DISK").get(0).workflowId());
        assertEquals(1, service.listByDomain(null).size());

        OpsWorkflow workflow = workflow("wf-new", "cpu");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        assertTrue(service.upsert(workflow, true));
        verify(jdbc).update(anyString(), any(Object[].class));
        assertFalse(service.upsert(null, false));
        assertFalse(service.upsert(new OpsWorkflow(null, "cpu", List.of(), "", "", List.of(), "", null, 0, true), false));
    }

    @Test
    void persistenceHelpersFailClosedAndNormalizeRecentRuns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkflowMemoryService service = new WorkflowMemoryService(jdbc);
        ReflectionTestUtils.setField(service, "enabled", true);

        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(2, 1, 0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(4);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 1L, "workflow_id", "wf-1", "trace_id", "t-1", "success", 1,
                        "steps_ok", 2, "steps_total", 2, "summary", "ok", "created_at", "now"),
                Map.of("id", 2L, "workflow_id", "wf-1", "trace_id", "t-2", "success", true,
                        "steps_ok", 1, "steps_total", 2, "summary", "partial", "created_at", "now")));

        service.recordUtility("wf-1");
        service.recordSuccess("wf-1");
        service.recordRun("wf-1", "trace", true, 2, 2, "x".repeat(600));
        assertEquals(2, service.countRuns("wf-1"));
        assertEquals(1, service.getSuccessCount("wf-1"));
        assertEquals(4, service.countAllRuns());
        assertEquals(2, service.listRecentRuns("wf-1", 100).size());
        assertTrue((Boolean) service.listRecentRuns("wf-1", 100).get(0).get("success"));
        assertTrue((Boolean) service.listRecentRuns("wf-1", 100).get(1).get("success"));
        assertEquals(0, service.countRuns(null));
        assertEquals(0, service.getSuccessCount(" "));
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenThrow(new RuntimeException("read down"));
        assertEquals(0, service.countAllRuns());
        assertTrue(service.listRecentRuns(null, 10).isEmpty());

        doThrow(new RuntimeException("db down")).when(jdbc).update(anyString(), any(Object[].class));
        service.recordUtility("wf-1");
        service.recordSuccess("wf-1");
        service.recordRun("wf-1", "trace", false, 0, 2, null);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenThrow(new RuntimeException("read down"));
        assertEquals(0, service.countRuns("wf-1"));
        assertEquals(0, service.getSuccessCount("wf-1"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenThrow(new RuntimeException("read down"));
        assertEquals(0, service.countAllRuns());
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(new RuntimeException("read down"));
        assertTrue(service.listRecentRuns("wf-1", 10).isEmpty());
    }

    @Test
    void ensureTableReloadAndFromRowCoverDatabaseDegradation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkflowMemoryService service = new WorkflowMemoryService(jdbc);
        ReflectionTestUtils.setField(service, "enabled", false);
        service.reloadCache();
        assertEquals(0, service.countEnabled());

        doThrow(new RuntimeException("ddl down")).when(jdbc).execute(anyString());
        service.ensureTable();

        assertEquals(null, service.fromRow(Map.of("steps_json", "[]")).workflowId());
        assertFalse(service.existsWithToolSequence(null, null));
        assertFalse(service.existsWithToolSequence("disk", List.of("DiskTool")));
        assertTrue(service.listByDomain("missing").isEmpty());
    }

    @Test
    void enabledCacheAndDomainQueriesAreDefensive() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkflowMemoryService service = new WorkflowMemoryService(jdbc);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxWorkflowsPerDomain", 12);
        CopyOnWriteArrayList<OpsWorkflow> cache = (CopyOnWriteArrayList<OpsWorkflow>)
                ReflectionTestUtils.getField(service, "cache");
        cache.add(workflow("wf-1", "service"));
        assertTrue(service.isEnabled());
        assertEquals(1, service.countByDomain("service"));
        assertEquals(0, service.countByDomain("cpu"));
        assertFalse(service.isDomainFull("cpu"));
        assertTrue(service.existsWithToolSequence(null, List.of("DiskTool", "LogCleanupTool")) == false);
        assertTrue(service.listEnabled().size() == 1);
    }

    private static Map<String, Object> row(String id, String domain, String steps, int utility, int enabled) {
        return Map.of(
                "workflow_id", id, "domain_tag", domain, "finding_kinds", "A, B",
                "title", "title", "description", "desc", "steps_json", steps,
                "source_type", "online", "source_trace_id", "trace", "utility_count", utility,
                "enabled", enabled);
    }

    private static OpsWorkflow workflow(String id, String domain) {
        return new OpsWorkflow(id, domain, List.of("FINDING"), "title", "desc",
                List.of(OpsWorkflowStep.of("env", "reason", "DiskTool", Map.of("path", "{path}"))),
                "online", "trace", 0, true);
    }
}
