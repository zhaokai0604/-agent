package com.award.log.security;

import com.award.log.config.AgentOpsProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpsPathPolicyDeepCoverageTest {

    private OpsPathPolicy policy;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        AgentOpsProperties props = new AgentOpsProperties();
        AgentOpsProperties.Paths paths = new AgentOpsProperties.Paths();
        paths.setPolicyVersion("test-policy");
        paths.setWindowsReadPrefixes(List.of("C:/logs", "D:/data"));
        paths.setWindowsCleanRoots(List.of("C:/tmp"));
        paths.setWindowsLogCleanupRoots(List.of("C:/logs"));
        paths.setWindowsConfigPrefixes(List.of("C:/config"));
        paths.setWindowsPrivilegePrefixes(List.of("C:/bin"));
        paths.setDeniedSubstrings(List.of("secret", "/root/"));
        props.setPaths(paths);
        policy = new OpsPathPolicy(props);
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "logCollectorFilePath", "");
        policy.applyFrom(paths);
    }

    @Test
    void normalizationTraversalDenialAndSnapshotsAreDeterministic() {
        assertEquals("D:/var/log", OpsPathPolicy.normalizeWindowsPath(" d:\\var\\log\\ "));
        assertEquals("", OpsPathPolicy.normalizeWindowsPath("  "));
        assertEquals("/var/log", policy.normalizeUnixPath("\\var\\log///"));
        assertEquals("", policy.normalizeUnixPath("   "));
        assertTrue(policy.hasTraversalOrNullBytes("C:/tmp/../secret"));
        assertTrue(policy.hasTraversalOrNullBytes("bad\0path"));
        assertFalse(policy.matchesDenied("C:/logs/app.log"));
        assertTrue(policy.matchesDenied("C:/secret/app.log"));
        assertEquals("test-policy", policy.getPolicyVersion());
        assertTrue(policy.rejectReason("read").startsWith("路径策略拦截[test-policy]"));
    }

    @Test
    void categoryAllowListsRespectBoundaryAndDeniedPrefixes() {
        assertTrue(policy.isAllowedLogReadPath("C:/logs/app.log"));
        assertFalse(policy.isAllowedLogReadPath("C:/logs2/app.log"));
        assertTrue(policy.isAllowedCleanDirectory("C:/tmp/cache"));
        assertFalse(policy.isAllowedCleanDirectory("C:/tmp2"));
        assertTrue(policy.isAllowedLogCleanupPath("C:/logs/old.log"));
        assertTrue(policy.isAllowedConfigCheckPath("C:/config/app.conf"));
        assertTrue(policy.isAllowedPrivilegeProbePath("C:/bin/tool.exe"));
        assertTrue(policy.isAllowedDiskInsightRoot("C:/logs"));
        assertFalse(policy.isAllowedLogReadPath("C:/secret/app.log"));
        assertFalse(policy.isAllowedPrivilegeProbePath("//server/share"));
    }

    @Test
    void editableListsAndWindowsDerivedRootsCanBeUpdated() {
        policy.updateEditablePathLists(true, List.of("E:/logs/", "E:/logs"), List.of("E:/tmp/"), List.of("E:/logs"));
        assertTrue(policy.isAllowedLogReadPath("E:/logs/app.log"));
        assertTrue(policy.isAllowedCleanDirectory("E:/tmp/cache"));
        assertTrue(policy.isAllowedLogCleanupPath("E:/logs/old.log"));
        assertEquals(List.of("E:/logs", "E:/logs"), policy.snapshotReadPrefixes());
        assertEquals(List.of("E:/tmp"), policy.snapshotTempCleanRoots());
        assertEquals(List.of("E:/logs"), policy.snapshotLogCleanupRoots());
    }
}
