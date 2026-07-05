package com.award.log.config;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.impl.AiAuditLogService;
import com.award.log.support.PojoExerciseSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfigPackageCoverageTest {

    @TempDir
    Path tempDir;

    @Test
    void propertiesBeansExposeDefaults() {
        StandardEnvironment env = new StandardEnvironment();
        PojoExerciseSupport.exerciseAll(
                AgentOpsProperties.class,
                KnowledgeProperties.class,
                OpsDryRunProperties.class,
                ManagementPortProperties.class,
                AppCorsProperties.class);

        SystemConfigRuntimeState runtimeState = new SystemConfigRuntimeState(env);
        runtimeState.init();
        assertFalse(runtimeState.getPatrolInspectRoots().isEmpty());
        assertEquals("HYBRID", runtimeState.getAutoRemediationMode());

        AgentOpsProperties agent = new AgentOpsProperties();
        assertNotNull(agent.getPaths());
        assertNotNull(agent.getServiceRestart());
        assertNotNull(agent.getLogSafety());

        ManagementPortProperties mgmt = new ManagementPortProperties();
        mgmt.setEnabled(true);
        mgmt.setPort(8089);
        assertTrue(mgmt.isActive(8088));
        assertFalse(mgmt.isActive(8089));
    }

    @Test
    void startupConfigValidatorWarnsInNonProd() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        env.setProperty("spring.datasource.url", "jdbc:h2:mem:test");
        env.setProperty("spring.datasource.username", "sa");

        StartupConfigValidator validator = new StartupConfigValidator(env);
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void ttlCacheExpiresEntries() throws InterruptedException {
        TtlConcurrentMapCacheManager manager = new TtlConcurrentMapCacheManager(50L, "probe");
        Cache cache = manager.getCache("probe");
        assertNotNull(cache);
        cache.put("k", "v");
        assertNotNull(cache.get("k"));
        Thread.sleep(60);
        assertNull(cache.get("k"));
    }

    @Test
    void systemConfigFileSupportRoundTrip() throws Exception {
        Path file = tempDir.resolve("override.json");
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("patrolInspectRoots", List.of("logs", "tmp"));
        runtime.put("healthCheckPorts", List.of(8088, 9090));
        runtime.put("dryRunGlobal", true);
        root.put("runtime", runtime);
        root.put("collector", Map.of("fileRoot", "logs"));
        root.put("ai", Map.of("baseUrl", "http://127.0.0.1:9", "chatModel", "demo"));

        SystemConfigFileSupport.writeJsonFile(file, root);
        Map<String, Object> read = SystemConfigFileSupport.readJsonFile(file);
        assertFalse(read.isEmpty());

        Map<String, Object> props = SystemConfigFileSupport.toSpringProperties(read, Map.of(), "secret");
        assertTrue(props.containsKey("ops.dry-run.global"));
        assertEquals("logs,tmp", props.get("ops.patrol.inspect-roots"));

        String cipher = SystemConfigFileSupport.encryptSecret("api-key-123", "unit-test-secret");
        String plain = SystemConfigFileSupport.decryptSecret(cipher, "unit-test-secret");
        assertEquals("api-key-123", plain);

        Map<String, Object> withSecret = SystemConfigFileSupport.toSpringProperties(
                read,
                Map.of("secretOps", Map.of("aiApiKey", cipher)),
                "unit-test-secret");
        assertEquals("api-key-123", withSecret.get("spring.ai.openai.api-key"));
        assertNotNull(SystemConfigFileSupport.latestSavedAt(file));
    }

    @Test
    void aiAuditFilterPersistsWhenServiceAvailable() throws ServletException, IOException {
        AiAuditLogService auditLogService = mock(AiAuditLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiAuditLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditLogService);

        RequestUserResolver resolver = mock(RequestUserResolver.class);
        when(resolver.currentUserId(any())).thenReturn(42);
        when(resolver.currentUserRole(any())).thenReturn(1);

        AiAuditFilter filter = new AiAuditFilter(provider, resolver);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mcp/tools");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(200);

        filter.doFilter(request, response, chain);

        verify(auditLogService).save(eq(42), eq(1), eq("127.0.0.1"), eq("GET"),
                eq("/api/mcp/tools"), eq(200), anyLong(), anyInt());
    }

    @Test
    void aiRateLimitFilterBlocksBurstRequests() throws Exception {
        AiRateLimitFilter filter = new AiRateLimitFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/award-log/api/assistant/chat/stream");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(200);

        for (int i = 0; i < 60; i++) {
            filter.doFilter(request, response, chain);
            assertEquals(200, response.getStatus());
        }
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
    }

    @Test
    void systemConfigHelpersHandleEdgeCases() {
        assertTrue(SystemConfigFileSupport.stringList("a,b").contains("a"));
        assertEquals(List.of(8088, 9090), SystemConfigFileSupport.integerList("8088,9090"));
        assertTrue(SystemConfigFileSupport.nestedMap(null).isEmpty());
        assertNotNull(SystemConfigFileSupport.defaultWorkingDir());
        assertFalse(SystemConfigFileSupport.candidateDirectories().isEmpty());
    }
}
