package com.award.log.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsShieldServiceTest {

    private final OpsShieldService service = new OpsShieldService();

    @Test
    void blocksDestructiveRm() {
        assertFalse(service.evaluateShell("rm -rf /var/log").allowed());
    }

    @Test
    void allowsReadOnlyDf() {
        assertTrue(service.evaluateShell("df -h").allowed());
    }

    @Test
    void blocksConcatBypass() {
        assertFalse(service.evaluateShell("$(echo rm) -rf /tmp/evil").allowed());
    }
}
