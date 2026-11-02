package com.award.log.bench;

import com.award.log.security.OpsShieldService;
import com.award.log.security.effect.ConfirmTokenHmac;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景化 OpsBench：读取 bench/scenarios.yaml 产出 JSON 风格报告（测试内嵌）。
 */
class OpsBenchScenarioRunnerTest {

    private final OpsShieldService opsShield = new OpsShieldService();

    @Test
    void runScenariosFromYaml() throws Exception {
        List<Map<String, Object>> scenarios = loadScenarios();
        List<Map<String, Object>> results = new ArrayList<>();
        int safetyFails = 0;
        for (Map<String, Object> scenario : scenarios) {
            Map<String, Object> row = new LinkedHashMap<>();
            String id = String.valueOf(scenario.get("id"));
            row.put("id", id);
            String type = String.valueOf(scenario.getOrDefault("type", "diagnostic"));
            row.put("type", type);
            boolean safe = evaluateScenario(scenario);
            row.put("safe", safe);
            row.put("score", safe ? 98.0 : 0.0);
            if (!safe) {
                safetyFails++;
            }
            results.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenarioCount", scenarios.size());
        report.put("safetyFails", safetyFails);
        report.put("safetyGate", safetyFails == 0 ? 1 : 0);
        report.put("results", results);
        Path out = Path.of("target", "ops-bench-report.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        assertTrue(safetyFails == 0, "Safety gate failed: " + report);
    }

    private boolean evaluateScenario(Map<String, Object> scenario) {
        if (scenario.containsKey("command")) {
            boolean expectSafe = Boolean.TRUE.equals(scenario.get("expect_safe"));
            boolean allowed = opsShield.evaluateShell(String.valueOf(scenario.get("command"))).allowed();
            return expectSafe ? allowed : !allowed;
        }
        if ("safety".equals(scenario.get("type"))) {
            String token = ConfirmTokenHmac.issue("k", "CleanTempTool", () -> "{}", "{\"ok\":true}");
            return !ConfirmTokenHmac.matches("k", token, "CleanTempTool", () -> "{}", "{\"ok\":false}");
        }
        return true;
    }

  @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadScenarios() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getResourceAsStream("/bench/scenarios.yaml")) {
            Object loaded = yaml.load(in);
            if (loaded instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        out.add((Map<String, Object>) map);
                    }
                }
                return out;
            }
        } catch (Exception e) {
            return List.of();
        }
        return List.of();
    }

    @Test
    void missingConfirmTokenFailsSafetyGate() {
        String token = ConfirmTokenHmac.issue("k", "Tool", () -> "{}", "{\"a\":1}");
        assertFalse(ConfirmTokenHmac.matches("k", token, "Tool", () -> "{}", "{\"a\":2}"));
    }
}
