package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.mapper.AlarmRuleMapper;
import com.award.log.model.AlarmRuleEntity;
import com.award.log.service.RuleRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlarmRuleCompatControllerCoverageTest {

    @Mock private AlarmRuleMapper mapper;
    @Mock private RuleRegistryService registry;

    private AlarmRuleCompatController controller;
    private AlarmRuleEntity entity;

    @BeforeEach
    void setUp() {
        controller = new AlarmRuleCompatController(mapper, registry);
        entity = new AlarmRuleEntity();
        entity.setId(9L);
        entity.setName("rule");
        entity.setDescription("description");
        entity.setRuleType("KEYWORD");
        entity.setRuleExpression("LEVEL = ERROR");
        entity.setSeverity("ERROR");
        entity.setPushChannels("BOTH");
        entity.setEnabled(true);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        lenient().when(registry.save(any())).thenAnswer(invocation -> {
            com.award.log.model.RuleDefinition rule = invocation.getArgument(0);
            if (rule.getId() == null || rule.getId().isBlank()) {
                rule.setId("9");
            }
            return rule;
        });
        lenient().when(mapper.selectById(anyLong())).thenReturn(entity);
    }

    @Test
    void updateDeleteGetAndListCoverPersistenceBoundaries() {
        assertEquals(500, controller.update(Map.of()).getCode());
        assertEquals(500, controller.update(Map.of("id", "bad")).getCode());
        when(mapper.selectById(8L)).thenReturn(null);
        assertEquals(500, controller.update(Map.of("id", 8L)).getCode());

        Result<Map<String, Object>> updated = controller.update(new HashMap<>(Map.of(
                "id", 9L, "name", "updated", "expression", "LEVEL = WARN", "enabled", false)));
        assertEquals(200, updated.getCode());
        assertEquals(9L, updated.getData().get("id"));

        when(registry.delete("9")).thenReturn(true);
        assertTrue(controller.delete(9).getData());
        when(registry.delete("10")).thenReturn(false);
        assertFalse(controller.delete(10).getData());

        when(mapper.selectById(10L)).thenReturn(null);
        assertEquals(500, controller.get(10).getCode());
        assertEquals(200, controller.get(9).getCode());
        when(mapper.selectAll()).thenReturn(List.of(entity));
        when(mapper.selectEnabled()).thenReturn(List.of(entity));
        assertEquals(1, controller.list().getData().size());
        assertEquals(1, controller.enabled().getData().size());
    }

    @Test
    void enableAndTestCoverMissingRowsAndSeverityPayloadMapping() {
        when(mapper.updateEnabled(1L, true)).thenReturn(0);
        assertEquals(500, controller.enable(1, true).getCode());

        when(mapper.updateEnabled(9L, false)).thenReturn(1);
        when(mapper.selectById(9L)).thenReturn(entity);
        assertEquals(200, controller.enable(9, false).getCode());
        assertNotNull(controller.enable(9, false).getData());

        when(mapper.selectById(12L)).thenReturn(null);
        assertEquals(500, controller.test(12, "ERROR").getCode());
        when(mapper.selectById(9L)).thenReturn(entity);
        when(registry.testRule(any(), any())).thenReturn(true);
        assertTrue(controller.test(9, "FATAL failure").getData().get("matched") instanceof Boolean);
        controller.test(9, "WARN alert");
        controller.test(9, "INFO startup");
        controller.test(9, "");
    }

    @Test
    void addAcceptsLegacyFieldNamesAndMapsPatternCompatibility() {
        Map<String, Object> camel = new HashMap<>(Map.of(
                "name", "camel", "ruleExpression", "LEVEL = ERROR", "ruleType", "keyword",
                "severity", "WARN", "pushChannels", "web", "enabled", "false"));
        assertEquals(200, controller.add(camel).getCode());

        assertEquals(200, controller.add(new HashMap<>(Map.of(
                "name", "direct", "expression", "LEVEL = INFO", "rule_type", "keyword",
                "push_channels", "email", "severity", "FATAL"))).getCode());
        assertEquals(200, controller.add(new HashMap<>(Map.of("name", "underscore", "rule_expression", "COUNT > 1"))).getCode());
        assertEquals(200, controller.add(new HashMap<>(Map.of("name", "error-pattern", "pattern", "exception"))).getCode());
        assertEquals(200, controller.add(new HashMap<>(Map.of("name", "warn-pattern", "pattern", "alert"))).getCode());
        assertEquals(200, controller.add(new HashMap<>(Map.of("name", "info-pattern", "pattern", "info"))).getCode());
        assertEquals(200, controller.add(new HashMap<>(Map.of("name", "other-pattern", "pattern", "heartbeat"))).getCode());
    }
}
