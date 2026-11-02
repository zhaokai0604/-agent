package com.award.log.mcp.dispatch;

/** 分发层统一返回：成功带 JSON data，失败带 errorMessage。 */
public record McpToolDispatchResult(boolean success, String data, String errorMessage) {

    public static McpToolDispatchResult ok(String data) {
        return new McpToolDispatchResult(true, data, null);
    }

    public static McpToolDispatchResult fail(String error) {
        return new McpToolDispatchResult(false, null, error);
    }
}
