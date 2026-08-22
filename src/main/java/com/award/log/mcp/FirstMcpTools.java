package com.award.log.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 对话 {@code @Tool} 实验用占位（服务器时间 / echo），不执行操作系统命令；HTTP 面默认禁止。
 */
@Component
public class FirstMcpTools {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(name = "getServerTime", description = "获取当前服务器时间，返回格式为 yyyy-MM-dd HH:mm:ss")
    public String getServerTime() {
        return LocalDateTime.now().format(FORMATTER);
    }

    @Tool(name = "echo", description = "回显传入的字符串参数，在字符串前加上 'Echo: ' 前缀返回")
    public String echo(@ToolParam(description = "需要回显的字符串消息") String message) {
        return "Echo: " + message;
    }
}
