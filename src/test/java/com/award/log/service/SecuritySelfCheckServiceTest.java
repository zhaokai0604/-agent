package com.award.log.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SecuritySelfCheckServiceTest {

    @Autowired
    private SecuritySelfCheckService securitySelfCheckService;

    @Test
    void allProbesShouldPass() {
        Map<String, Object> report = securitySelfCheckService.run();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        int failed = ((Number) summary.get("failed")).intValue();
        if (failed > 0) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> probes = (List<Map<String, Object>>) report.get("probes");
            StringBuilder sb = new StringBuilder("failed probes:\n");
            for (Map<String, Object> p : probes) {
                if (!Boolean.TRUE.equals(p.get("passed"))) {
                    sb.append(p.get("id")).append(" expect=").append(p.get("expect"))
                            .append(" actual=").append(p.get("actual")).append('\n');
                }
            }
            throw new AssertionError(sb.toString());
        }
        assertEquals("PASS", report.get("overallStatus"));
        assertTrue(((Number) summary.get("total")).intValue() >= 8);
    }
}
