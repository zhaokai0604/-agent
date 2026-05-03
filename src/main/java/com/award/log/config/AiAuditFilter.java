package com.award.log.config;

import com.award.log.security.AuthInterceptor;
import com.award.log.service.impl.AiAuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class AiAuditFilter extends OncePerRequestFilter {

    private final ObjectProvider<AiAuditLogService> auditLogServiceProvider;

    public AiAuditFilter(ObjectProvider<AiAuditLogService> auditLogServiceProvider) {
        this.auditLogServiceProvider = auditLogServiceProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/award-log/api/chat/")
                || path.startsWith("/award-log/log/diagnose/")
                || path.startsWith("/award-log/api/assistant/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        Object role = session == null ? null : session.getAttribute(AuthInterceptor.SESSION_USER_ROLE);
        String path = request.getRequestURI();
        String method = request.getMethod();
        String remoteIp = request.getRemoteAddr();
        int contentLength = request.getContentLength();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            try {
                AiAuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
                if (auditLogService != null) {
                    auditLogService.save(userId, role, remoteIp, method, path, response.getStatus(), elapsed, Math.max(contentLength, 0));
                }
            } catch (Exception ignored) {
                // avoid breaking business path because of audit persistence issue
            }
            log.info("AI_AUDIT userId={} role={} ip={} method={} path={} status={} durationMs={} reqBytes={}",
                    userId, role, remoteIp, method, path, response.getStatus(), elapsed, Math.max(contentLength, 0));
        }
    }
}
