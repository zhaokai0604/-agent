package com.award.log.security;

import com.award.log.config.InjectionGuardProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Prompt Injection 边车客户端（可选 FastAPI 推理服务）。
 */
@Slf4j
@Component
public class InjectionGuardSidecarClient {

    private final InjectionGuardProperties properties;
    private final HttpClient httpClient;

    public InjectionGuardSidecarClient(InjectionGuardProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, properties.getTimeoutMs())))
                .build();
    }

    public boolean isMalicious(String text) {
        if (!properties.isSidecarEnabled() || text == null || text.isBlank()) {
            return false;
        }
        try {
            String json = "{\"text\":\"" + escape(text) + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getSidecarUrl().replaceAll("/$", "") + "/scan"))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            String body = response.body();
            return body != null && body.contains("\"malicious\":true");
        } catch (Exception e) {
            log.debug("Injection sidecar unavailable: {}", e.getMessage());
            return false;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
