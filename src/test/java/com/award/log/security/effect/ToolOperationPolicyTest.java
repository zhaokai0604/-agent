package com.award.log.security.effect;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolOperationPolicyTest {

    private final ToolEffectResolver resolver = new ToolEffectResolver();

    @Test
    void mixedToolsAreReadOnlyOnlyForObservationOperations() {
        assertFalse(ToolOperationPolicy.isWriteOperation("ProcessTool", Map.of("operation", "list")));
        assertTrue(ToolOperationPolicy.isWriteOperation("ProcessTool", Map.of("operation", "kill")));
        assertFalse(ToolOperationPolicy.isWriteOperation("DockerTool", Map.of("operation", "inspect")));
        assertTrue(ToolOperationPolicy.isWriteOperation("DockerTool", Map.of("operation", "stop")));
        assertFalse(ToolOperationPolicy.isWriteOperation("SystemdTool", Map.of("operation", "status")));
        assertTrue(ToolOperationPolicy.isWriteOperation("SystemdTool", Map.of("operation", "restart")));
    }

    @Test
    void pureObservationToolsNeverBecomeWriteOperations() {
        assertFalse(ToolOperationPolicy.isWriteCapableTool("FirewallTool"));
        assertFalse(ToolOperationPolicy.isWriteOperation("FirewallTool", Map.of()));
        assertFalse(ToolOperationPolicy.isWriteOperation("ConfigCheckTool", Map.of()));
        assertFalse(ToolOperationPolicy.isWriteOperation("K8sTool", Map.of("operation", "get-pods")));
    }

    @Test
    void effectResolverMatchesOperationPermission() {
        assertFalse(resolver.resolve("DiskOpsTool", Map.of("operation", "df")).writeEffect());
        assertTrue(resolver.resolve("DiskOpsTool", Map.of("operation", "clean-temp")).writeEffect());
        assertFalse(resolver.resolve("ProcessTool", Map.of("operation", "list")).writeEffect());
        assertTrue(resolver.resolve("ProcessTool", Map.of("operation", "kill")).writeEffect());
        assertFalse(resolver.resolve("FirewallTool", Map.of()).writeEffect());
    }

    @Test
    void operationPermissionsExposeTheSameCanonicalDecision() {
        var permissions = ToolOperationPolicy.operationPermissions(
                "ServiceOpsTool", java.util.List.of("status", "restart"));
        assertEquals("READ_ONLY", permissions.get("status"));
        assertEquals("WRITE_REQUESTED", permissions.get("restart"));
        assertEquals("READ_ONLY", permissions.get("default"));
    }
}
