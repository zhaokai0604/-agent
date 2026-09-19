package com.award.log.service;

import com.award.log.agent.AutonomousOpsOrchestrator;
import com.award.log.config.AgentOpsProperties;
import com.award.log.config.OpsDryRunProperties;
import com.award.log.config.SystemConfigRuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemConfigServiceCoverageTest {

    @Mock private Environment environment;
    @Mock private AgentPathPolicyService pathPolicyService;
    @Mock private OpsPatrolService patrolService;
    @Mock private OpsAutoRemediationService remediationService;
    @Mock private AutonomousOpsOrchestrator autonomousOpsOrchestrator;

    private SystemConfigRuntimeState runtimeState;
    private SystemConfigService service;

    @BeforeEach
    void setUp() {
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[]{"dev", "test"});
        lenient().when(environment.getProperty(anyString())).thenAnswer(invocation -> property(invocation.getArgument(0), null));
        lenient().when(environment.getProperty(anyString(), anyString())).thenAnswer(invocation ->
                property(invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(pathPolicyService.getEffectivePolicyView()).thenReturn(Map.of(
                "readPrefixes", List.of("C:/logs"),
                "cleanRoots", List.of("C:/Temp"),
                "logCleanupRoots", List.of("C:/logs"),
                "serviceRestartAllowlist", List.of("nginx"),
                "deniedSubstrings", List.of("/etc/shadow")));

        runtimeState = new SystemConfigRuntimeState(environment);
        runtimeState.setPatrolInspectRoots(List.of("C:/logs", "C:/Temp"));
        runtimeState.setHealthCheckPorts(List.of(8088, 80));
        runtimeState.setPingTarget("127.0.0.1");
        runtimeState.setAutoRemediationEnabled(true);
        runtimeState.setAutoRemediationMode("HYBRID");
        runtimeState.setDryRunGlobal(false);
        runtimeState.setPatrolDiskWarnPercent(80);
        runtimeState.setPatrolCpuWarnPercent(85);
        runtimeState.setAnomalySpikeFactor(2);
        runtimeState.setErrorAlarmMin(3);
        runtimeState.setAutoRiskPatrolAutoMax(6);
        runtimeState.setAutoProposeTempCleanDiskMin(80);
        runtimeState.setAutoProposeLogCleanDiskMin(85);

        service = new SystemConfigService(environment, runtimeState, pathPolicyService, new AgentOpsProperties());
        ReflectionTestUtils.setField(service, "opsPatrolService", patrolService);
        ReflectionTestUtils.setField(service, "opsAutoRemediationService", remediationService);
        ReflectionTestUtils.setField(service, "autonomousOpsOrchestrator", autonomousOpsOrchestrator);
        ReflectionTestUtils.setField(service, "opsDryRunProperties", new OpsDryRunProperties());
    }

    @Test
    void effectiveConfigAggregatesRuntimeCollectorAiPathAndFiles() {
        Map<String, Object> result = service.getEffectiveConfig();

        assertNotNull(result.get("platform"));
        assertTrue(((Map<?, ?>) result.get("groups")).containsKey("runtime"));
        assertTrue(((Map<?, ?>) result.get("groups")).containsKey("collector"));
        assertTrue(((Map<?, ?>) result.get("groups")).containsKey("ai"));
        assertTrue(((Map<?, ?>) result.get("groups")).containsKey("pathPolicy"));
        assertEquals(3, ((List<?>) result.get("files")).size());
        assertTrue(((List<?>) result.get("messages")).size() >= 2);
    }

    @Test
    void runtimeCollectorAiPathAndSecretGroupsNormalizeAndHotApply() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("patrolInspectRoots", List.of("C:/logs"));
        runtime.put("healthCheckPorts", List.of(8088, 443));
        runtime.put("pingTarget", "localhost");
        runtime.put("autoRemediationEnabled", "false");
        runtime.put("autoRemediationMode", "confirm_first");
        runtime.put("dryRunGlobal", true);
        runtime.put("patrolDiskWarnPercent", "81.5");
        runtime.put("patrolCpuWarnPercent", 86);
        runtime.put("anomalySpikeFactor", "2.5");
        runtime.put("errorAlarmMin", "4");
        runtime.put("autoRiskPatrolAutoMax", "5.5");
        runtime.put("autoProposeTempCleanDiskMin", 79);
        runtime.put("autoProposeLogCleanDiskMin", "84");

        ReflectionTestUtils.invokeMethod(service, "saveRuntimeGroup", root, runtime);
        assertEquals(List.of("C:/logs"), runtimeState.getPatrolInspectRoots());
        assertEquals(List.of(8088, 443), runtimeState.getHealthCheckPorts());
        assertEquals("CONFIRM_FIRST", runtimeState.getAutoRemediationMode());
        assertTrue(runtimeState.isDryRunGlobal());
        verify(patrolService).applyHotConfig(any(), anyDouble(), anyDouble(), anyDouble(), anyInt());
        verify(remediationService).applyHotConfig(false, "CONFIRM_FIRST", 5.5, 79, 84);
        verify(autonomousOpsOrchestrator).applyHotConfig("8088,443", "localhost");

        Map<String, Object> collector = Map.of(
                "fileRoot", "C:/logs", "includeExtensions", ".log,.txt", "excludeDirectories", "archive",
                "networkEnabled", true, "networkPort", "1514", "networkProtocol", "tcp",
                "dbEnabled", false, "dbQuery", "select 1");
        ReflectionTestUtils.invokeMethod(service, "saveCollectorGroup", root, collector);

        Map<String, Object> ai = Map.of(
                "baseUrl", "https://ai.example.com", "chatModel", "model-a", "embeddingModel", "embed-a");
        ReflectionTestUtils.invokeMethod(service, "saveAiGroup", root, ai);

        Map<String, Object> path = Map.of(
                "readPrefixes", List.of("C:/logs"), "cleanRoots", List.of("C:/Temp"),
                "logCleanupRoots", List.of("C:/logs"), "serviceRestartAllowlist", List.of("nginx.service"));
        ReflectionTestUtils.invokeMethod(service, "savePathPolicyGroup", root, path);
        verify(pathPolicyService).saveEditablePolicy(any(Map.class));

        Map<String, Object> secretRoot = new LinkedHashMap<>();
        ReflectionTestUtils.invokeMethod(service, "saveSecretOpsGroup", secretRoot, Map.of("aiApiKey", "new-key"));
        assertTrue(((Map<?, ?>) secretRoot.get("secretOps")).containsKey("aiApiKey"));
    }

    @Test
    void normalizationRejectsInvalidRuntimeValuesAndSupportsNumericEquivalence() {
        Map<String, Object> root = new LinkedHashMap<>();
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(service, "saveRuntimeGroup", root,
                Map.of("patrolInspectRoots", List.of("C:/logs"), "healthCheckPorts", List.of(0))));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(service, "saveRuntimeGroup", root,
                Map.of("patrolInspectRoots", List.of("C:/logs"), "healthCheckPorts", List.of(80), "pingTarget", "bad host")));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(service, "saveRuntimeGroup", root,
                Map.of("patrolInspectRoots", List.of("C:/logs"), "healthCheckPorts", List.of(80), "autoRemediationMode", "unknown")));

        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "normalizeBoolean", true));
        assertEquals(2.5d, ((Number) ReflectionTestUtils.invokeMethod(service, "parseNumber", "2.5")).doubleValue());
        assertEquals(3, ((Number) ReflectionTestUtils.invokeMethod(service, "parseNumber", "3")).intValue());
        assertEquals("runtime", ReflectionTestUtils.invokeMethod(service, "propertyGroup", "ops.patrol.disk-warn-percent"));
        assertEquals("collector", ReflectionTestUtils.invokeMethod(service, "propertyGroup", "log.collector.file.path"));
        assertEquals("ai", ReflectionTestUtils.invokeMethod(service, "propertyGroup", "spring.ai.openai.base-url"));
    }

    @Test
    void normalizationHelpersCoverListNumericUrlAndSavedValueBoundaries() {
        assertEquals(List.of("a", "b"), ReflectionTestUtils.invokeMethod(
                service, "normalizeStringList", " a, ,b ", false, "列表"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizeStringList", List.of(), true, "列表"));

        assertEquals(List.of(1, 65535), ReflectionTestUtils.invokeMethod(
                service, "normalizePortList", "1, 65535"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizePortList", "abc"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizePortList", List.of(0)));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizePortList", List.of(65536)));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizePortList", List.of()));

        assertEquals("host", ReflectionTestUtils.invokeMethod(service, "normalizeHost", " host ", "主机"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizeHost", "bad host", "主机"));
        assertEquals("TCP", ReflectionTestUtils.invokeMethod(service, "normalizeEnum",
                "tcp", List.of("UDP", "TCP"), "协议"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(service, "normalizeEnum",
                "SCTP", List.of("UDP", "TCP"), "协议"));

        assertEquals(50.0, (Double) ReflectionTestUtils.invokeMethod(
                service, "normalizePercent", "50", "比例"), 0.001);
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(service,
                "normalizePercent", 0, "比例"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(service,
                "normalizePercent", 101, "比例"));
        assertEquals(2.5, (Double) ReflectionTestUtils.invokeMethod(
                service, "normalizePositiveDouble", "2.5", "倍率"), 0.001);
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(service,
                "normalizePositiveDouble", 0, "倍率"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(service,
                "normalizePositiveDouble", "bad", "倍率"));
        assertEquals(10, ((Number) ReflectionTestUtils.invokeMethod(
                service, "normalizeIntRange", "10", 1, 20, "数量")).intValue());
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizeIntRange", 0, 1, 20, "数量"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizeIntRange", "bad", 1, 20, "数量"));

        assertEquals("https://example.com", ReflectionTestUtils.invokeMethod(
                service, "normalizeUrl", " https://example.com ", "地址"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizeUrl", "ftp://example.com", "地址"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizeUrl", "not a url", "地址"));
        assertEquals("text", ReflectionTestUtils.invokeMethod(
                service, "normalizeText", " text ", true, "文本"));
        assertNull(ReflectionTestUtils.invokeMethod(
                service, "normalizeText", null, false, "文本"));
        assertThrowsIllegalArgument(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizeText", " ", true, "文本"));

        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "normalizeLiveValue", "boolean", "false"));
        assertEquals(2, ((Number) ReflectionTestUtils.invokeMethod(
                service, "normalizeLiveValue", "number", "2")).intValue());
        assertEquals("raw", ReflectionTestUtils.invokeMethod(service, "normalizeLiveValue", "text", "raw"));
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "normalizeSavedValue", "boolean", "true"));
        assertEquals(2.5, ((Number) ReflectionTestUtils.invokeMethod(
                service, "normalizeSavedValue", "number", 2.5)).doubleValue(), 0.001);
        assertEquals("raw", ReflectionTestUtils.invokeMethod(service, "normalizeSavedValue", "text", "raw"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "normalizeLiveValue", "text", null));
        assertNull(ReflectionTestUtils.invokeMethod(service, "normalizeSavedValue", "text", null));
        assertNull(ReflectionTestUtils.invokeMethod(service, "parseNumber", " "));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "valuesEqual", 2, 2.0));
        assertTrue(!(Boolean) ReflectionTestUtils.invokeMethod(service, "valuesEqual", "2", 2));
    }

    private static void assertThrowsIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ex) {
            assertTrue(ex.getMessage() != null || ex.getCause() != null);
            return;
        }
        throw new AssertionError("expected invalid configuration to be rejected");
    }

    private static String property(String key, String fallback) {
        return switch (key) {
            case "APP_CONFIG_SECRET" -> "secret";
            case "spring.ai.openai.api-key" -> "";
            case "log.collector.file.path" -> "C:/logs";
            case "log.collector.file.include-extensions" -> ".log";
            case "log.collector.file.exclude-directories" -> "archive";
            case "log.collector.network.enabled" -> "false";
            case "log.collector.network.port" -> "514";
            case "log.collector.network.protocol" -> "UDP";
            case "log.collector.db.enabled" -> "false";
            case "log.collector.db.query" -> "select 1";
            case "spring.ai.openai.base-url" -> "https://ai.example.com";
            case "spring.ai.openai.chat.options.model" -> "model-a";
            case "spring.ai.openai.embedding.options.model" -> "embed-a";
            default -> fallback;
        };
    }
}
