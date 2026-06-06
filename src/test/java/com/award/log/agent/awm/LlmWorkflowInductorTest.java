package com.award.log.agent.awm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmWorkflowInductorTest {

    private final LlmWorkflowInductor inductor = new LlmWorkflowInductor(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void parseValidJsonWorkflow() {
        String json = """
                {
                  "title": "磁盘压力诊断与清理",
                  "description": "分区占用高时先诊断再 dryRun 清理",
                  "steps": [
                    {"envDesc": "根分区>85%", "reason": "采集分区", "toolName": "DiskTool", "argsTemplate": {}},
                    {"envDesc": "热点已定位", "reason": "预览清临时", "toolName": "CleanTempTool",
                     "argsTemplate": {"path": "{temp-path}", "days": "7", "dryRun": "{dryRun}"}}
                  ]
                }
                """;
        Optional<OpsWorkflow> wf = inductor.parseAndValidate(json, "trace-1", "disk", List.of("DISK_PRESSURE"));
        assertTrue(wf.isPresent());
        assertEquals("llm", wf.get().sourceType());
        assertEquals(2, wf.get().steps().size());
        assertEquals("CleanTempTool", wf.get().steps().get(1).toolName());
    }

    @Test
    void rejectsUnknownTool() {
        String json = """
                {"title":"x","description":"y","steps":[
                  {"envDesc":"a","reason":"b","toolName":"UnknownTool","argsTemplate":{}},
                  {"envDesc":"c","reason":"d","toolName":"DiskTool","argsTemplate":{}}
                ]}
                """;
        assertTrue(inductor.parseAndValidate(json, "t", "disk", List.of()).isEmpty());
    }

    @Test
    void rejectsLiteralPathInArgs() {
        String json = """
                {"title":"x","description":"y","steps":[
                  {"envDesc":"a","reason":"b","toolName":"DiskTool","argsTemplate":{}},
                  {"envDesc":"c","reason":"d","toolName":"CleanTempTool",
                   "argsTemplate":{"path":"/etc/passwd","dryRun":"true"}}
                ]}
                """;
        assertTrue(inductor.parseAndValidate(json, "t", "disk", List.of()).isEmpty());
    }

    @Test
    void extractJsonFromFence() {
        String raw = "说明\n```json\n{\"title\":\"t\"}\n```";
        assertEquals("{\"title\":\"t\"}", LlmWorkflowInductor.extractJsonBlock(raw));
    }
}
