package com.award.log.security.effect;

import com.award.log.config.OpsPepProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RepairRegressionTest {
    @Test void nonRunningServiceStatesCannotPassSubstringCheck() {
        for (String state : List.of("inactive", "not running", "broken", "ok", "active (exited)")) {
            Map<String,Object> params = Map.of("dryRun", false, "confirmRestart", true, "serviceName", "nginx");
            var effect = new ToolEffectResolver().resolve("ServiceRestartTool", params);
            var result = RemediationVerifyEvaluator.evaluate("ServiceRestartTool", Map.of("success", true,
                    "data", Map.of("mode", "EXECUTED", "service", "nginx", "afterState", state)), "", params,
                    RemediationContractFactory.build("ServiceRestartTool", params, effect, "user"), effect);
            assertEquals(false, result.get("verified"), state);
        }
    }

    @Test void missingOrUnavailableBeforeMetricsNeverVerifyCleanup() {
        var params = Map.<String,Object>of("dryRun", false, "confirmDelete", true);
        var effect = new ToolEffectResolver().resolve("CleanTempTool", params);
        var body = new LinkedHashMap<String,Object>();
        body.put("success", true);
        body.put("data", Map.of("mode", "DELETE", "filesDeleted", 1));
        var contract = RemediationContractFactory.build("CleanTempTool", params, effect, "user");
        assertEquals("EVIDENCE_INCOMPLETE", RemediationVerifyEvaluator.evaluate("CleanTempTool", body, "", params, contract, effect).get("verifyOutcome"));
        body.put("metricsBefore", Map.of("available", false, "filesFound", 1));
        body.put("metricsAfter", Map.of("available", true, "filesFound", 0));
        assertEquals(false, RemediationVerifyEvaluator.evaluate("CleanTempTool", body, "", params, contract, effect).get("verified"));
    }

    @Test void emptyKeysFailClosedAndLocalKeysDifferAcrossProcesses() {
        assertThrows(IllegalStateException.class, () -> ConfirmTokenHmac.issue("", "CleanTempTool", () -> "{}", "{}"));
        assertFalse(ConfirmTokenHmac.matches("", "token", "CleanTempTool", () -> "{}", "{}"));
        assertNotEquals(new OpsPepProperties().getConfirmHmacKey(), new OpsPepProperties().getConfirmHmacKey());
    }

    @Test void concurrentReservationsCannotExceedBudget() throws Exception {
        var service = new SessionRiskBudgetService(60_000, 3, 100);
        var effect = new ToolEffect(EffectAction.DELETE, "PATH", "/tmp/test", 1, true, "DELETE_PATH");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i=0; i<100; i++) tasks.add(() -> service.reserve("same-user", effect).allowed());
            long accepted = 0;
            for (Future<Boolean> future : pool.invokeAll(tasks)) if (future.get()) accepted++;
            assertEquals(3, accepted);
            assertEquals(3, service.summary("same-user").get("realWrites"));
            assertEquals(0, service.summary("other-user").get("realWrites"));
        } finally { pool.shutdownNow(); }
    }
}
