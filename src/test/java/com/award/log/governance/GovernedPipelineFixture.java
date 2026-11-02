package com.award.log.governance;

import com.award.log.mcp.tools.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.stubbing.Answer;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 编排展示测试的依赖替身：指标为明确的测试输入，真实执行另由集成测试验证。 */
public final class GovernedPipelineFixture {
    private GovernedPipelineFixture() { }
    public static void install(Object target, CleanTempTool clean, LogCleanupTool logs, ServiceRestartTool restart) {
        ObjectMapper mapper = new ObjectMapper();
        OpsExecutionPipeline pipeline = mock(OpsExecutionPipeline.class);
        try {
            Answer<OpsExecutionPipeline.PipelineResult> answer = call -> {
                String tool = call.getArgument(0);
                Map<String,Object> params = call.getArgument(1);
                boolean preview = call.getMethod().getName().equals("preview");
                String path = String.valueOf(params.get("path"));
                int days = ((Number)params.getOrDefault("days", 7)).intValue();
                String raw = switch(tool) {
                    case "CleanTempTool" -> target.getClass().getSimpleName().equals("OpsAutoRemediationService")
                        ? clean.cleanTempFiles(path, days, preview, !preview)
                        : clean.cleanTempFiles(path, days, preview, !preview, Boolean.TRUE.equals(params.get("removeDirectory")));
                    case "LogCleanupTool" -> logs.cleanupOldLogs(path, days, preview, !preview);
                    case "ServiceRestartTool" -> restart.restartService(String.valueOf(params.get("serviceName")), preview, !preview);
                    default -> throw new IllegalArgumentException(tool);
                };
                Map<String,Object> body = raw == null ? new LinkedHashMap<>(Map.of("success",false)) : mapper.readValue(raw, LinkedHashMap.class);
                if (!preview) {
                    body.put("metricsBefore", Map.of("available",true,"filesFound",2));
                    body.put("metricsAfter", Map.of("available",true,"filesFound",0));
                    var evaluator = new OpsExecutionPipeline(null,null,null,null,null,null,null,null,null);
                    body.put("verify", evaluator.verify(tool,body,"",params));
                }
                return new OpsExecutionPipeline.PipelineResult(body,"test",0,tool,null,"","");
            };
            lenient().when(pipeline.executeDirect(anyString(),anyMap(),anyString(),anyString())).thenAnswer(answer);
            lenient().when(pipeline.preview(anyString(),anyMap(),anyString(),anyString())).thenAnswer(answer);
            ReflectionTestUtils.setField(target,"opsExecutionPipeline",pipeline);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
