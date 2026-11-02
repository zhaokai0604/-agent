package com.award.log.security.effect;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmTokenHmacTest {

    @Test
    void hmacChangesWhenPreviewChanges() {
        String key = "test-key";
        String token1 = ConfirmTokenHmac.issue(key, "CleanTempTool", () -> "{days=7}", "{\"success\":true}");
        String token2 = ConfirmTokenHmac.issue(key, "CleanTempTool", () -> "{days=7}", "{\"success\":false}");
        assertFalse(token1.equals(token2));
    }

    @Test
    void hmacMatchesCanonicalPayload() {
        String key = "test-key";
        String preview = "{\"preview\":true}";
        String token = ConfirmTokenHmac.issue(key, "DiskTool", () -> "{}", preview);
        assertTrue(ConfirmTokenHmac.matches(key, token, "DiskTool", () -> "{}", preview));
        assertFalse(ConfirmTokenHmac.matches(key, token, "DiskTool", () -> "{path=/tmp}", preview));
    }
}
