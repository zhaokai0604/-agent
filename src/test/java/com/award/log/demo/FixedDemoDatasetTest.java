package com.award.log.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedDemoDatasetTest {

    @Test
    void demoDatasetKeepsStableClosedLoopAssertions() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream(
                "/demo/threshcore-demo-dataset.json")) {
            JsonNode dataset = mapper.readTree(input);
            assertEquals("threshcore-demo-v1", dataset.path("datasetId").asText());
            assertEquals(1, dataset.path("expected").path("preview").path("deletableCount").asInt());
            assertEquals(2, dataset.path("expected").path("preview").path("protectedSkipped").asInt());
            assertTrue(dataset.path("expected").path("execute")
                    .path("protectedFilesPreserved").asBoolean());
            assertTrue(dataset.path("expected").path("restore").path("restored").asBoolean());
        }
        try (InputStream log = getClass().getResourceAsStream("/demo/threshcore-demo.log")) {
            String content = new String(log.readAllBytes());
            assertTrue(content.contains("cleanup candidate"));
            assertTrue(content.contains("protected file"));
        }
    }
}
