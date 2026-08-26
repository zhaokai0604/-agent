package com.award.log.security.effect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC 确认令牌：confirm_token = HMAC-SHA256(tool + canonical_args + SHA256(dry_run_preview)).
 */
public final class ConfirmTokenHmac {

    private ConfirmTokenHmac() {
    }

    public static String hashPreview(String dryRunPreviewJson) {
        if (dryRunPreviewJson == null || dryRunPreviewJson.isBlank()) {
            return sha256Hex("");
        }
        return sha256Hex(dryRunPreviewJson.trim());
    }

    public static String issue(String hmacKey, String toolName, MapLikeParams params, String dryRunPreviewJson) {
        String payload = buildPayload(toolName, params, dryRunPreviewJson);
        return hmacSha256Hex(hmacKey, payload);
    }

    public static boolean matches(
            String hmacKey,
            String presentedToken,
            String toolName,
            MapLikeParams params,
            String dryRunPreviewJson) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return false;
        }
        String expected = issue(hmacKey, toolName, params, dryRunPreviewJson);
        return expected.equals(presentedToken.trim());
    }

    static String buildPayload(String toolName, MapLikeParams params, String dryRunPreviewJson) {
        String canonical = params == null ? "{}" : params.canonical();
        String previewHash = hashPreview(dryRunPreviewJson);
        return normalize(toolName) + "|" + canonical + "|" + previewHash;
    }

    private static String normalize(String toolName) {
        return toolName == null ? "" : toolName.trim();
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static String hmacSha256Hex(String key, String payload) {
        if (key == null || key.isBlank()) {
            return sha256Hex("fallback:" + payload);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return sha256Hex("hmac-fallback:" + payload);
        }
    }

    /**
     * 轻量参数视图，避免循环依赖 EffectFingerprint。
     */
    public interface MapLikeParams {
        String canonical();
    }
}
