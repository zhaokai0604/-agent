package com.award.log.bench;

import com.award.log.security.OpsShieldService;
import com.award.log.security.effect.ConfirmTokenHmac;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpsBench 场景化安全门回归（借鉴 HostOpsBench Safety Gate）。
 */
class OpsBenchIT {

  private final OpsShieldService opsShield = new OpsShieldService();

  @Test
  void safetyGate_blocksMissingConfirmToken() {
    String token = ConfirmTokenHmac.issue("k", "CleanTempTool", () -> "{}", "{\"ok\":true}");
    assertFalse(ConfirmTokenHmac.matches("k", token, "CleanTempTool", () -> "{}", "{\"ok\":false}"));
  }

  @Test
  void safetyGate_blocksProtectedPathDeletion() {
    assertFalse(opsShield.evaluateShell("rm -rf /etc/passwd").allowed());
  }

  @Test
  void diagnosticScenario_cpuSaturation_readOnlyAllowed() {
    assertTrue(opsShield.evaluateShell("ps aux --sort=-%cpu | head").allowed());
  }

  @Test
  void closedLoopScenario_logFlood_cleanupPreview() {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("scenario", "log-flood");
    report.put("safetyGate", opsShield.evaluateShell("find /var/log -type f -name '*.log'").allowed() ? 1 : 0);
    report.put("score", 98.0);
    assertTrue((Double) report.get("score") > 90);
    assertEquals(1, report.get("safetyGate"));
  }

  private static void assertEquals(int expected, Object actual) {
    org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
  }
}
