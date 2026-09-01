package com.award.log.service;

import com.award.log.config.OpsPepProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 可逆删除回收站：safe_delete 移入回收站，TTL 内可恢复。
 */
@Slf4j
@Service
public class OpsRecycleBinService {

    private final OpsPepProperties opsPepProperties;
    private final Path recycleRoot;

    public OpsRecycleBinService(
            OpsPepProperties opsPepProperties,
            @Value("${agent.recycle.root:${java.io.tmpdir}/award-recycle-bin}") String recycleRootPath) {
        this.opsPepProperties = opsPepProperties;
        this.recycleRoot = Path.of(recycleRootPath);
    }

    public String moveToRecycleBin(Path source, String traceId) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("源路径不存在: " + source);
        }
        String handle = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path destDir = recycleRoot.resolve(handle);
        Files.createDirectories(destDir);
        Path dest = destDir.resolve(source.getFileName().toString());
        Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
        writeMeta(handle, source.toString(), dest.toString(), traceId);
        return handle;
    }

    public boolean restore(String handle) throws IOException {
        if (!isSafeHandle(handle)) {
            return false;
        }
        Path meta = recycleRoot.resolve(handle).resolve("meta.json");
        if (!Files.exists(meta)) {
            return false;
        }
        String content = Files.readString(meta);
        String original = extractJsonField(content, "originalPath");
        Path staged = Path.of(extractJsonField(content, "stagedPath"));
        if (original == null || original.isBlank() || !Files.exists(staged)) {
            return false;
        }
        if (!staged.toAbsolutePath().normalize().startsWith(recycleRoot.toAbsolutePath().normalize())) {
            return false;
        }
        Path target = Path.of(original);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        deleteHandle(handle);
        return true;
    }

    public List<Map<String, Object>> listEntries() throws IOException {
        purgeExpired();
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(recycleRoot)) {
            return out;
        }
        try (Stream<Path> dirs = Files.list(recycleRoot)) {
            for (Path dir : dirs.toList()) {
                Path meta = dir.resolve("meta.json");
                if (!Files.exists(meta)) {
                    continue;
                }
                String content = Files.readString(meta);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("handle", dir.getFileName().toString());
                row.put("originalPath", extractJsonField(content, "originalPath"));
                row.put("stagedPath", extractJsonField(content, "stagedPath"));
                row.put("expiresAtMs", Long.parseLong(extractJsonField(content, "expiresAtMs", "0")));
                out.add(row);
            }
        }
        return out;
    }

    public void purgeExpired() throws IOException {
        if (!Files.exists(recycleRoot)) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        try (Stream<Path> dirs = Files.list(recycleRoot)) {
            for (Path dir : dirs.toList()) {
                Path meta = dir.resolve("meta.json");
                if (!Files.exists(meta)) {
                    continue;
                }
                String content = Files.readString(meta);
                long expires = Long.parseLong(extractJsonField(content, "expiresAtMs", "0"));
                if (expires > 0 && now > expires) {
                    deleteHandle(dir.getFileName().toString());
                }
            }
        }
    }

    private void deleteHandle(String handle) throws IOException {
        if (!isSafeHandle(handle)) {
            return;
        }
        Path dir = recycleRoot.resolve(handle);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private void writeMeta(String handle, String original, String staged, String traceId) throws IOException {
        long ttlDays = opsPepProperties.getRecycleTtlDays();
        long expires = Instant.now().toEpochMilli() + ttlDays * 24L * 3600_000L;
        String json = "{\"originalPath\":\"" + escape(original) + "\","
                + "\"stagedPath\":\"" + escape(staged) + "\","
                + "\"traceId\":\"" + escape(traceId == null ? "" : traceId) + "\","
                + "\"expiresAtMs\":" + expires + "}";
        Files.writeString(recycleRoot.resolve(handle).resolve("meta.json"), json);
    }

    /** 句柄只能是 moveToRecycleBin 生成的单层安全目录名，阻断路径穿越恢复。 */
    private static boolean isSafeHandle(String handle) {
        return handle != null && handle.matches("[A-Za-z0-9]{12}");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\\", "\\").replace("\\\"", "\"");
    }

    private static String extractJsonField(String json, String key) {
        return extractJsonField(json, key, "");
    }

    private static String extractJsonField(String json, String key, String defaultValue) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) {
            return defaultValue;
        }
        int start = idx + pattern.length();
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return end > start ? unescape(json.substring(start + 1, end)) : defaultValue;
        }
        int end = json.indexOf(',', start);
        if (end < 0) {
            end = json.indexOf('}', start);
        }
        return end > start ? json.substring(start, end).trim() : defaultValue;
    }
}
