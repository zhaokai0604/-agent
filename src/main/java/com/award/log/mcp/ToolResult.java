package com.award.log.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResult<T> {
    private boolean success;
    private T data;
    private String error;
    private Long durationMs;
    private Boolean cacheHit;

    public static <T> ToolResult<T> success(T data, long durationMs, boolean cacheHit) {
        return new ToolResult<>(true, data, null, durationMs, cacheHit);
    }

    public static <T> ToolResult<T> success(T data, long durationMs) {
        return new ToolResult<>(true, data, null, durationMs, false);
    }

    public static <T> ToolResult<T> error(String errorMsg, long durationMs) {
        return new ToolResult<>(false, null, errorMsg, durationMs, false);
    }
}
