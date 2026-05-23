package com.award.log.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.award.log.handler.PerformanceWebSocketHandler;
import com.award.log.handler.LogWebSocketHandler;
import com.award.log.security.WebSocketAuthHandshakeInterceptor;

/**
 * WebSocket配置类
 * 用于启用WebSocket支持并注册WebSocket处理器
 */

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketAuthHandshakeInterceptor wsAuthInterceptor;
    private final PerformanceWebSocketHandler performanceWebSocketHandler;

    public WebSocketConfig(
            WebSocketAuthHandshakeInterceptor wsAuthInterceptor,
            PerformanceWebSocketHandler performanceWebSocketHandler) {
        this.wsAuthInterceptor = wsAuthInterceptor;
        this.performanceWebSocketHandler = performanceWebSocketHandler;
    }

    @Bean
    public LogWebSocketHandler logWebSocketHandler() {
        return new LogWebSocketHandler();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册性能监控WebSocket处理器
        registry.addHandler(performanceWebSocketHandler, "/ws/performance")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOrigins("*"); // 允许所有来源的连接，生产环境中应该限制
        
        // 注册日志监控WebSocket处理器
        registry.addHandler(logWebSocketHandler(), "/ws/logs")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOrigins("*");
    }
}
