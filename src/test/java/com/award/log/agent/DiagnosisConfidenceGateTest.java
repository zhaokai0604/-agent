package com.award.log.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisConfidenceGateTest {

    private final DiagnosisConfidenceGate gate = new DiagnosisConfidenceGate();

    @Test
    void requiresTwoIndependentSignals() {
        var single = gate.evaluate(List.of(
                DiagnosisConfidenceGate.cpuSignal("load high", true)), 2);
        assertFalse(single.confirmed());

        var dual = gate.evaluate(List.of(
                DiagnosisConfidenceGate.cpuSignal("load high", true),
                DiagnosisConfidenceGate.processSignal("java cpu 90%", true)), 2);
        assertTrue(dual.confirmed());
    }
}
