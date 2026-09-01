package com.award.log.mcp;

import com.award.log.governance.OpsExecutionPipeline;
import com.award.log.mcp.dispatch.McpToolParamReader;
import com.award.log.security.OpsSecurityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chat Agent write callback adapter. A diagnose call is executed as a preview
 * and registered in the same PEP pending store used by HTTP MCP. The model is
 * never allowed to turn a preview callback into an untracked real mutation.
 */
final class PepChatToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String pipelineToolName;
    private final OpsExecutionPipeline pipeline;
    private final ObjectMapper objectMapper;

    PepChatToolCallback(ToolCallback delegate, String pipelineToolName,
                        OpsExecutionPipeline pipeline, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.pipelineToolName = pipelineToolName;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String arguments) {
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx == null || ctx.isOpenToolPermissions() || ctx.isUserConfirmedWrite()) {
            return delegate.call(arguments);
        }
        try {
            Map<String, Object> params = objectMapper.readValue(
                    arguments == null || arguments.isBlank() ? "{}" : arguments,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            McpToolParamReader.applyDryRunDefaults(pipelineToolName, params);
            String preview = delegate.call(objectMapper.writeValueAsString(params));
            OpsExecutionPipeline.PipelineResult proposed = pipeline.propose(
                    pipelineToolName,
                    params,
                    ctx.getUserMessage(),
                    ctx.getRequester(),
                    preview);
            com.award.log.security.ChatToolExecutionTracker.recordPendingConfirmation(proposed.body());
            return objectMapper.writeValueAsString(proposed.body());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具参数不是有效 JSON", e);
        }
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        return call(arguments);
    }
}
