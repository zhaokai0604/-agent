package com.award.log.security.effect;

import java.util.Map;
import java.util.UUID;

/**
 * 一次性写能力凭证：确认后签发，绑定效果指纹、dry-run 预览哈希、HMAC 确认令牌与请求方。
 */
public record WriteCapabilityToken(
        String tokenId,
        String effectFingerprint,
        String toolName,
        String requester,
        long issuedAtMs,
        long expiresAtMs,
        String dryRunPreviewHash,
        String confirmTokenHmac
) {

    public static WriteCapabilityToken issue(
            String effectFingerprint,
            String toolName,
            String requester,
            long ttlMs) {
        return issue(effectFingerprint, toolName, requester, ttlMs, "", "");
    }

    public static WriteCapabilityToken issue(
            String effectFingerprint,
            String toolName,
            String requester,
            long ttlMs,
            String dryRunPreviewJson,
            String hmacKey,
            Map<String, Object> parameters) {
        String previewHash = ConfirmTokenHmac.hashPreview(dryRunPreviewJson);
        String canonical = EffectFingerprint.canonicalParams(parameters);
        String confirm = ConfirmTokenHmac.issue(
                hmacKey,
                toolName,
                () -> canonical,
                dryRunPreviewJson);
        return issue(effectFingerprint, toolName, requester, ttlMs, previewHash, confirm);
    }

    public static WriteCapabilityToken issue(
            String effectFingerprint,
            String toolName,
            String requester,
            long ttlMs,
            String dryRunPreviewHash,
            String confirmTokenHmac) {
        long now = System.currentTimeMillis();
        long ttl = Math.max(60_000L, ttlMs);
        return new WriteCapabilityToken(
                UUID.randomUUID().toString().replace("-", ""),
                effectFingerprint == null ? "" : effectFingerprint,
                toolName == null ? "" : toolName,
                requester == null || requester.isBlank() ? "anonymous" : requester.trim(),
                now,
                now + ttl,
                dryRunPreviewHash == null ? "" : dryRunPreviewHash,
                confirmTokenHmac == null ? "" : confirmTokenHmac);
    }

    public boolean matches(String presentedTokenId, String expectedFingerprint, String expectedRequester, long nowMs) {
        if (presentedTokenId == null || presentedTokenId.isBlank()) {
            return false;
        }
        if (!tokenId.equals(presentedTokenId.trim())) {
            return false;
        }
        if (nowMs > expiresAtMs) {
            return false;
        }
        if (expectedFingerprint != null && !expectedFingerprint.equals(effectFingerprint)) {
            return false;
        }
        String req = expectedRequester == null || expectedRequester.isBlank() ? "anonymous" : expectedRequester.trim();
        return requester.equals(req);
    }

    public boolean matchesConfirmHmac(
            String presentedConfirmToken,
            String hmacKey,
            String dryRunPreviewJson) {
        if (confirmTokenHmac == null || confirmTokenHmac.isBlank()) {
            return presentedConfirmToken == null || presentedConfirmToken.isBlank();
        }
        if (presentedConfirmToken == null || presentedConfirmToken.isBlank()) {
            return false;
        }
        return ConfirmTokenHmac.matches(
                hmacKey,
                presentedConfirmToken,
                toolName,
                () -> effectFingerprint,
                dryRunPreviewJson);
    }
}
