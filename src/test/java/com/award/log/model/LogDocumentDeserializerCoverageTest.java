package com.award.log.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogDocumentDeserializerCoverageTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void deserializesObjectWithScalarObjectArrayAndTimestampVariants() throws Exception {
        String json = "{" +
                "\"id\":123,\"content\":{\"text\":\"ERROR\"}," +
                "\"severity\":\"ERROR\",\"protocol\":\"http\",\"source\":\"app\",\"taskId\":\"t1\"," +
                "\"anomalyScore\":\"0.75\",\"anomaly\":\"true\",\"@timestamp\":\"2024-01-01 10:00:00\"," +
                "\"anomalyReasons\":[\"error\",{\"code\":500}]," +
                "\"anomalyReasonsObj\":[\"obj-text\",{\"reason\":\"timeout\"}]" +
                "}";
        LogDocument document = mapper.readValue(json, LogDocument.class);
        assertEquals("123", document.getId());
        assertEquals("ERROR", document.getContent());
        assertEquals(0.75, document.getAnomalyScore());
        assertTrue(document.getAnomaly());
        assertEquals(4, document.getAnomalyReasons().size());
        assertNotNull(document.getTimestamp());

        LogDocument arrayDocument = mapper.readValue(
                "[{\"timestamp\":\"1704067200000\",\"anomalyReasons\":\"[\\\"a\\\", \\\"b\\\"]\"}]",
                LogDocument.class);
        assertNotNull(arrayDocument.getTimestamp());
        assertEquals(2, arrayDocument.getAnomalyReasons().size());
    }

    @Test
    void deserializerHandlesEmptyScalarInvalidValuesAndAlternativeReasonFields() throws Exception {
        LogDocument emptyArray = mapper.readValue("[]", LogDocument.class);
        assertNull(emptyArray.getId());
        LogDocument primitive = mapper.readValue("1", LogDocument.class);
        assertNull(primitive.getId());
        LogDocument invalid = mapper.readValue(
                "{\"anomalyScore\":\"bad\",\"anomaly\":false,\"timestamp\":\"not-a-date\",\"anomaly_reasons\":\"single\"}",
                LogDocument.class);
        assertNull(invalid.getAnomalyScore());
        assertFalse(invalid.getAnomaly());
        assertNull(invalid.getTimestamp());
        assertEquals(java.util.List.of("single"), invalid.getAnomalyReasons());
    }
}
