package com.award.log.service;

import com.award.log.model.patrol.PatrolCorrelationSnapshot;
import com.award.log.model.patrol.PatrolFinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatrolHistoryServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private PatrolHistoryService service;

    @BeforeEach
    void setUp() {
        service = new PatrolHistoryService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "historyRetentionDays", 14);
    }

    @Test
    void ensureTableSwallowsErrors() {
        doThrow(new RuntimeException("no ddl")).when(jdbcTemplate).execute(anyString());
        assertDoesNotThrow(() -> service.ensureTable());
    }

    @Test
    void saveRunPersistsFindingPayload() {
        PatrolFinding finding = PatrolFinding.of("WARN", "DISK_HIGH", "disk pressure", "usage high");

        service.saveRun(new PatrolCorrelationSnapshot(), List.of(finding));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                eq("INSERT INTO ops_patrol_history(patrol_time, finding_count, correlation_json, findings_json) VALUES (?,?,?,?)"),
                args.capture());
        assertEquals(1, args.getValue()[1]);
    }

    @Test
    void listHistoryMapsRows() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(9L);
                    when(rs.getTimestamp("patrol_time"))
                            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2024, 6, 1, 10, 0)));
                    when(rs.getInt("finding_count")).thenReturn(2);
                    when(rs.getString("correlation_json")).thenReturn("{\"alerts\":1}");
                    when(rs.getString("findings_json")).thenReturn("[{\"code\":\"X\"}]");
                    when(rs.getTimestamp("created_at"))
                            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2024, 6, 1, 10, 1)));
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<Map<String, Object>> rows = service.listHistory(7, 10);
        assertEquals(1, rows.size());
        assertEquals(9L, rows.get(0).get("id"));
        assertEquals(2, rows.get(0).get("findingCount"));
    }

    @Test
    void countByDayAggregatesTrend() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyInt()))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getDate("day")).thenReturn(Date.valueOf("2024-06-01"));
                    when(rs.getLong("alert_count")).thenReturn(5L);
                    when(rs.getLong("run_count")).thenReturn(2L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<Map<String, Object>> trend = service.countByDay(7);
        assertEquals(1, trend.size());
        assertEquals(5L, trend.get(0).get("alertCount"));
        assertEquals(2L, trend.get(0).get("runCount"));
    }

    @Test
    void purgeExpiredHistoryRemovesOldRows() {
        when(jdbcTemplate.update(anyString(), anyInt())).thenReturn(3);
        service.purgeExpiredHistory();
        verify(jdbcTemplate).update(contains("DELETE FROM ops_patrol_history"), eq(14));
    }
}
