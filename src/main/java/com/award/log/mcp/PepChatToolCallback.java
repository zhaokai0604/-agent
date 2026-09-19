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
    private final boolean allowWrite;

    PepChatToolCallback(ToolCallback delegate, String pipelineToolName,
                        OpsExecutionPipeline pipeline, ObjectMapper objectMapper,
                        boolean allowWrite) {
        this.delegate = delegate;
        this.pipelineToolName = pipelineToolName;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
        this.allowWrite = allowWrite;
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
        try {
            Map<String, Object> params = objectMapper.readValue(
                    arguments == null || arguments.isBlank() ? "{}" : arguments,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            String userMessage = ctx == null ? "chat agent write" : ctx.getUserMessage();
            String requester = ctx == null ? "chat-agent" : ctx.getRequester();
            if (allowWrite) {
                OpsExecutionPipeline.PipelineResult executed = pipeline.executeDirect(
                        pipelineToolName, params, userMessage, requester);
                return objectMapper.writeValueAsString(executed.body());
            }
            McpToolParamReader.applyDryRunDefaults(pipelineToolName, params);
            OpsExecutionPipeline.PipelineResult preview = pipeline.preview(
                    pipelineToolName, params, userMessage, requester);
            OpsExecutionPipeline.PipelineResult proposed = pipeline.propose(
                    pipelineToolName, params, userMessage, requester,
                    objectMapper.writeValueAsString(preview.body()),
                    ctx == null ? "" : ctx.getSessionId());
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
