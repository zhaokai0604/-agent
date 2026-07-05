package com.award.log.service.impl;

import com.award.log.agent.AssistantReplyPlanner;
import com.award.log.agent.MultiAgentContextBuilder;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedAssistantServiceTest {

    @Mock
    private StatisticsService statisticsService;
    @Mock
    private AiLogAlarmService aiLogAlarmService;
    @Mock
    private MultiAgentContextBuilder multiAgentContextBuilder;

    private UnifiedAssistantService service;

    @BeforeEach
    void setUp() {
        service = new UnifiedAssistantService();
        ReflectionTestUtils.setField(service, "statisticsService", statisticsService);
        ReflectionTestUtils.setField(service, "aiLogAlarmService", aiLogAlarmService);
        ReflectionTestUtils.setField(service, "multiAgentContextBuilder", multiAgentContextBuilder);
        ReflectionTestUtils.setField(service, "assistantReplyPlanner", new AssistantReplyPlanner());
        ReflectionTestUtils.setField(service, "knowledgeSearchTopK", 5);
        ReflectionTestUtils.setField(service, "defaultUseToolAgent", true);
        ReflectionTestUtils.setField(service, "orchestratorEnabled", true);
        ReflectionTestUtils.setField(service, "toolAgentStreamChunkChars", 900);
    }

    @Test
    void getAssistantContextShouldAggregateSections() {
        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of("cpuUsage", 12.0));
        when(statisticsService.getTaskStatusStatistics()).thenReturn(Map.of("statusCount", Map.of("DONE", 1)));
        when(aiLogAlarmService.getAlarmStatistics(1, null, null)).thenReturn(Map.of("totalAlarms", 0L));
        when(aiLogAlarmService.getAlarmHistory(1, 5, null, null)).thenReturn(Map.of("list", List.of()));
        when(multiAgentContextBuilder.buildForUser(null)).thenReturn(Map.of("diagnosisAgent", Map.of()));

        Map<String, Object> context = service.getAssistantContext();
        assertTrue(context.containsKey("agentHostOs"));
        assertTrue(context.containsKey("performance"));
        assertTrue(context.containsKey("taskStats"));
        assertTrue(context.containsKey("alarmStats"));
    }

    @Test
    void isChitchatMessageShouldRecognizeGreetingsAndPunctuation() {
        assertTrue(service.isChitchatMessage("你好呀"));
        assertTrue(service.isChitchatMessage("您好！"));
        assertTrue(service.isChitchatMessage("?"));
        assertTrue(service.isChitchatMessage("？"));
        assertTrue(service.isChitchatMessage("嗨"));
        assertFalse(service.isChitchatMessage("磁盘满了"));
        assertFalse(service.isChitchatMessage("帮我检查一下系统"));
    }

    @Test
    void shouldRouteToToolAgentShouldSkipChitchat() {
        assertFalse(service.shouldRouteToToolAgent("你好呀", true));
        assertFalse(service.shouldRouteToToolAgent("?", true));
        assertTrue(service.shouldRouteToToolAgent("磁盘满了", true));
    }

    @Test
    void getAssistantContextShouldTolerateDependencyFailures() {
        when(statisticsService.getSystemPerformance(null)).thenThrow(new RuntimeException("down"));
        when(statisticsService.getTaskStatusStatistics()).thenThrow(new RuntimeException("down"));
        when(aiLogAlarmService.getAlarmStatistics(1, null, null)).thenThrow(new RuntimeException("down"));
        when(aiLogAlarmService.getAlarmHistory(1, 5, null, null)).thenThrow(new RuntimeException("down"));
        when(multiAgentContextBuilder.buildForUser("check disk")).thenThrow(new RuntimeException("down"));

        Map<String, Object> context = service.getAssistantContext("check disk");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) context.get("performance")).get("unavailable"));
        assertEquals(List.of(), context.get("recentAlarms"));
    }
}
