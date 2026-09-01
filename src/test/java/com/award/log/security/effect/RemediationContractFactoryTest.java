package com.award.log.security.effect;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RemediationContractFactoryTest {

    @Test
    void cleanTempContractDeclaresPathDecreaseAndRecycleRecover() {
        ToolEffect effect = new ToolEffectResolver().resolve(
                "CleanTempTool", Map.of("path", "/tmp", "dryRun", false, "confirmDelete", true));
        WriteRemediationContract contract = RemediationContractFactory.build(
                "CleanTempTool",
                Map.of("path", "/tmp", "operator", "alice", "ticketId", "WO-1"),
                effect,
                "alice");

        assertEquals("PATH_USAGE", contract.expectedEffect().metric());
        assertEquals("DECREASE", contract.expectedEffect().direction());
        assertTrue(contract.recover().reversible());
        assertEquals("RecycleBinTool", contract.recover().recoverTool());
        assertEquals("alice", contract.approval().operator());
        assertEquals("WO-1", contract.approval().ticketId());
        assertEquals("MEDIUM", contract.approval().riskLevel());
    }

    @Test
    void serviceRestartContractMarksIrreversibleAndDualControl() {
        ToolEffect effect = new ToolEffectResolver().resolve(
                "ServiceRestartTool", Map.of("serviceName", "nginx"));
        WriteRemediationContract contract = RemediationContractFactory.build(
                "ServiceRestartTool",
                Map.of("serviceName", "nginx"),
                effect,
                "bob");

        assertEquals("SERVICE_STATE", contract.expectedEffect().metric());
        assertEquals("active", contract.expectedEffect().targetValue());
        assertFalse(contract.recover().reversible());
        assertTrue(contract.recover().irreversibilityNote().contains("不可撤销"));
        assertEquals("HIGH", contract.approval().riskLevel());
        assertTrue(contract.approval().requireSecondPerson());
    }
}
