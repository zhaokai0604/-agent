package com.award.log.config;

import com.award.log.handler.LogWebSocketHandler;
import com.award.log.handler.PerformanceWebSocketHandler;
import com.award.log.security.WebSocketAuthHandshakeInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketAuthHandshakeInterceptor wsAuthInterceptor;
    private final PerformanceWebSocketHandler performanceWebSocketHandler;
    private final AppCorsProperties corsProperties;

    public WebSocketConfig(WebSocketAuthHandshakeInterceptor wsAuthInterceptor,
                           PerformanceWebSocketHandler performanceWebSocketHandler,
                           AppCorsProperties corsProperties) {
        this.wsAuthInterceptor = wsAuthInterceptor;
        this.performanceWebSocketHandler = performanceWebSocketHandler;
        this.corsProperties = corsProperties;
    }

    @Bean
    public LogWebSocketHandler logWebSocketHandler() {
        return new LogWebSocketHandler();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] allowedOrigins = corsProperties.getAllowedOriginPatterns().toArray(String[]::new);
        registry.addHandler(performanceWebSocketHandler, "/ws/performance")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOriginPatterns(allowedOrigins);
        registry.addHandler(logWebSocketHandler(), "/ws/logs")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
