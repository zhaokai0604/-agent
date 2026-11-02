package com.award.log.agent.skill;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class SkillVersionGovernanceTest {
    private static Resource yaml(String version,String tool) {
        return new ByteArrayResource(("schema_version: 1\nversion: "+version+"\nruntime_contract: ops-pep/v1\nid: disk-cleanup\nplaybook: disk-cleanup\ndescription: test\ntools: ["+tool+"]\nstop_conditions: [evidence_insufficient]\nred_lines: [no_write_without_pep]\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void reload(SkillCatalogLoader loader,Resource... resources) {
        try(var mocked=mockConstruction(PathMatchingResourcePatternResolver.class,
                (mock,context)->when(mock.getResources(anyString())).thenReturn(resources))) { loader.reload(); }
    }
    @Test void versionedUpgradeRejectsDriftAndDowngradeAndCanRollbackWithExpectedDigest() {
        var loader=new SkillCatalogLoader();
        var original=loader.status();
        reload(loader,yaml("1.1.0","DiskTool"));
        String upgraded=(String)loader.status().get("catalogFingerprint");
        assertNotEquals(original.get("catalogFingerprint"),upgraded);
        assertEquals("1.1.0",loader.find("disk-cleanup").version());
        assertThrows(UnsupportedOperationException.class,()->loader.find("disk-cleanup").tools().clear());
        reload(loader,yaml("1.1.0","DiskTool"));
        assertEquals(upgraded,loader.status().get("catalogFingerprint"));
        assertThrows(IllegalStateException.class,()->reload(loader,yaml("1.1.0","CleanTempTool")));
        assertThrows(IllegalStateException.class,()->reload(loader,yaml("1.0.9","DiskTool")));
        assertThrows(IllegalStateException.class,()->loader.rollback("stale"));
        loader.rollback(upgraded);
        assertEquals(original.get("catalogFingerprint"),loader.status().get("catalogFingerprint"));
        assertEquals("1.0.0",loader.find("disk-cleanup").toView().get("version"));
    }
    @Test void rejectsUnknownToolsVersionsSchemaAndEmptyCatalogWithoutReplacingSnapshot() {
        var loader=new SkillCatalogLoader(); var before=loader.status();
        for(String version:List.of("1.0","01.0.0","1.0.0-beta","-1.0.0"))
            assertThrows(IllegalStateException.class,()->reload(loader,yaml(version,"DiskTool")));
        assertThrows(IllegalStateException.class,()->reload(loader,yaml("2.0.0","UnknownTool")));
        assertThrows(IllegalStateException.class,()->reload(loader));
        try {
            String valid=new String(yaml("2.0.0","DiskTool").getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            for(String invalid:List.of(valid.replace("schema_version: 1","schema_version: 2"),
                    valid.replace("ops-pep/v1","ops-pep/v9"), valid+"unexpected: true\n",
                    valid+"decision_rules: {confidence_gate: {min_independent_signals: 1.5}}\n"))
                assertThrows(IllegalStateException.class,()->reload(loader,new ByteArrayResource(invalid.getBytes(StandardCharsets.UTF_8))));
        } catch(java.io.IOException impossible) { throw new AssertionError(impossible); }
        assertEquals(before,loader.status());
        var view=loader.find("disk-cleanup").toView();
        assertEquals(64,((String)view.get("fingerprint")).length());
        assertEquals("ops-pep/v1",view.get("runtimeContract"));
    }
}
