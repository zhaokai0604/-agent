package com.award.log.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.award.log.security.WebSocketAuthHandshakeInterceptor;

/**
 * WebSocket配置类
 * 用于注册WebSocket处理器和端点
 */
@Configuration
@EnableWebSocket
public class WebSocketStreamConfig implements WebSocketConfigurer {

    private final WebSocketAuthHandshakeInterceptor wsAuthInterceptor;

    public WebSocketStreamConfig(WebSocketAuthHandshakeInterceptor wsAuthInterceptor) {
        this.wsAuthInterceptor = wsAuthInterceptor;
    }

    @Bean
    public LogStreamWebSocketHandler logStreamWebSocketHandler() {
        return new LogStreamWebSocketHandler();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册日志流WebSocket处理器（使用纯WebSocket模式）
        registry.addHandler(logStreamWebSocketHandler(), "/ws/log-stream")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOrigins("*");
    }
}
