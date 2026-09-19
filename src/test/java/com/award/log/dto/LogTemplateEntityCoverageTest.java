package com.award.log.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LogTemplateEntityCoverageTest {

    @Test
    void templateDefaultsAndMutableFieldsAreStable() {
        LogTemplateEntity entity = new LogTemplateEntity("t1", "ERROR <*> refused");
        assertEquals("t1", entity.getTemplateId());
        assertEquals(1, entity.getOccurCount().get());
        entity.getOccurCount().incrementAndGet();
        entity.setTemplateContent("WARN <*> timeout");
        entity.setParamList(List.of("host"));
        entity.setAnomalyScore(0.8);
        entity.setProtocolSet(Set.of("HTTP"));
        entity.setRiskWeight(1.2);
        assertEquals(2, entity.getOccurCount().get());
        assertEquals("WARN <*> timeout", entity.getTemplateContent());
        assertEquals(0.8, entity.getAnomalyScore());
        assertEquals(Set.of("HTTP"), entity.getProtocolSet());
        assertNotNull(entity.toString());
    }
}
