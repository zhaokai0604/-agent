package com.award.log.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * OpsShield：Shell 命令意图级拦截（拼接绕过、编码变体）。
 */
@Slf4j
@Component
public class OpsShieldService {

    private static final Pattern DESTRUCTIVE_SHELL = Pattern.compile(
            "(?i)(rm\\s+-rf\\s+/|mkfs\\.|dd\\s+if=|>\\s*/dev/sd|wipefs|shred\\s+-|chmod\\s+777\\s+/|"
                    + "chown\\s+.*\\s+/\\s*$|drop\\s+database|delete\\s+from)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CONCAT_BYPASS = Pattern.compile(
            "(?i)(\\$\\(|`[^`]*`|'\\s*rm\\s*'\\s*\\+|\"\\s*rm\\s*\"\\s*\\+|\\\\x[0-9a-f]{2})");

    public ShieldVerdict evaluateShell(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return ShieldVerdict.allow();
        }
        String normalized = commandLine.trim();
        if (DESTRUCTIVE_SHELL.matcher(normalized).find()) {
            return ShieldVerdict.block("OpsShield: destructive shell pattern detected");
        }
        if (CONCAT_BYPASS.matcher(normalized).find() && looksDestructiveAfterUnwrap(normalized)) {
            return ShieldVerdict.block("OpsShield: command concatenation bypass detected");
        }
        return ShieldVerdict.allow();
    }

    private boolean looksDestructiveAfterUnwrap(String cmd) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        return lower.contains("rm") || lower.contains("delete") || lower.contains("mkfs") || lower.contains("dd ");
    }

    public record ShieldVerdict(boolean allowed, String reason) {
        static ShieldVerdict allow() {
            return new ShieldVerdict(true, "");
        }

        static ShieldVerdict block(String reason) {
            return new ShieldVerdict(false, reason);
        }
    }
}
