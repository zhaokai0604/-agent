package com.award.log.service;

import com.award.log.config.OpsPepProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 可逆删除回收站：safe_delete 将对象移动到与源对象同卷的回收站，TTL 内可恢复。
 *
 * <p>回收站是安全边界的一部分：每个候选根必须显式配置，移动前强制比较
 * {@link FileStore}，绝不以 copy-delete 作为跨卷降级方案。句柄只由随机安全目录名
 * 组成，恢复时会在所有配置根中定位，从而支持进程重启后的恢复。</p>
 */
@Slf4j
@Service
public class OpsRecycleBinService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String META_FILE = "meta.json";
    private static final int HANDLE_LENGTH = 12;

    private final OpsPepProperties opsPepProperties;
    /** 保留旧字段，便于旧测试和运维诊断；它始终是第一个配置根。 */
    private final Path recycleRoot;
    private final List<Path> recycleRoots;
    private final int maxScanEntries;

    @Autowired(required = false)
    private com.award.log.config.OpsDryRunProperties dryRunProperties;

    /** 兼容现有单根构造器和单元测试。 */
    public OpsRecycleBinService(OpsPepProperties opsPepProperties, String recycleRootPath) {
        this(opsPepProperties, recycleRootPath, "", 10_000);
    }

    /** 兼容需要直接注入额外根的测试/嵌入式调用。 */
    public OpsRecycleBinService(OpsPepProperties opsPepProperties, String recycleRootPath, String volumeRoots) {
        this(opsPepProperties, recycleRootPath, volumeRoots, 10_000);
    }

    @Autowired
    public OpsRecycleBinService(
            OpsPepProperties opsPepProperties,
            @Value("${agent.recycle.root:${java.io.tmpdir}/award-recycle-bin}") String recycleRootPath,
            @Value("${agent.recycle.volume-roots:}") String volumeRoots,
            @Value("${agent.recycle.max-scan-entries:10000}") int maxScanEntries) {
        this.opsPepProperties = opsPepProperties;
        this.recycleRoots = parseRoots(recycleRootPath, volumeRoots);
        this.recycleRoot = this.recycleRoots.get(0);
        if (maxScanEntries < 1 || maxScanEntries > 1_000_000) {
            throw new IllegalArgumentException("agent.recycle.max-scan-entries 必须在 1..1000000 内");
        }
        this.maxScanEntries = maxScanEntries;
    }

    private static List<Path> parseRoots(String primary, String additional) {
        if (primary == null || primary.isBlank()) {
            throw new IllegalArgumentException("agent.recycle.root 不能为空");
        }
        Set<Path> unique = new LinkedHashSet<>();
        unique.add(Path.of(primary.trim()).toAbsolutePath().normalize());
        if (additional != null && !additional.isBlank()) {
            for (String raw : additional.split("[,\\r\\n]")) {
                if (!raw.isBlank()) {
                    unique.add(Path.of(raw.trim()).toAbsolutePath().normalize());
                }
            }
        }
        List<Path> roots = List.copyOf(unique);
        for (int i = 0; i < roots.size(); i++) {
            for (int j = i + 1; j < roots.size(); j++) {
                if (roots.get(i).startsWith(roots.get(j)) || roots.get(j).startsWith(roots.get(i))) {
                    throw new IllegalArgumentException("回收站根目录不得互相嵌套: " + roots.get(i) + " / " + roots.get(j));
                }
            }
        }
        return roots;
    }

    private void requireRealWritesEnabled() throws IOException {
        if (dryRunProperties != null && dryRunProperties.isGlobalDryRun()) {
            throw new IOException("全局演练模式已启用，回收站未执行写操作");
        }
    }

    public synchronized String moveToRecycleBin(Path source, String traceId) throws IOException {
        return moveToRecycleBin(source, traceId, null);
    }

    public synchronized String moveToRecycleBin(Path source, String traceId,
            com.award.log.security.effect.FileApprovalManifest.Target expected) throws IOException {
        requireRealWritesEnabled();
        if (source == null) {
            throw new IOException("源路径不能为空");
        }
        if (expected != null) {
            expected.verify(source, 2000, 256L * 1024 * 1024);
        }
        source = source.toAbsolutePath().normalize();
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("源路径不存在或为符号链接: " + source);
        }

        Path root = selectSameVolumeRoot(source);
        String handle = createHandle(root);
        Path destDir = root.resolve(handle).toAbsolutePath().normalize();
        Path dest = destDir.resolve(source.getFileName().toString()).normalize();
        if (!dest.startsWith(destDir) || Files.isSymbolicLink(destDir)) {
            throw new IOException("回收站目标目录不安全");
        }
        try {
            writeMeta(root, handle, source.toString(), dest.toString(), traceId);
            moveToStaging(source, dest);
        } catch (IOException e) {
            try {
                deleteHandleAt(root, handle);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        if (expected != null) {
            try {
                expected.verify(dest, 2000, 256L * 1024 * 1024);
            } catch (IOException mismatch) {
                try {
                    if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("原路径已被占用");
                    }
                    Files.move(dest, source);
                    deleteHandleAt(root, handle);
                } catch (IOException recovery) {
                    mismatch.addSuppressed(recovery);
                    throw new IOException("审批对象不一致；已保留回收条目，需人工恢复 handle=" + handle, mismatch);
                }
                throw new IOException("审批对象不一致，已移回原路径，未计为执行成功", mismatch);
            }
        }
        return handle;
    }

    /** 由测试或受控扩展点覆盖，但永远不能绕过同卷选择。 */
    protected void moveToStaging(Path source, Path destination) throws IOException {
        Files.move(source, destination);
    }

    /** 判断路径是否落在任一受信任回收根内。 */
    public boolean isRecyclePath(Path path) {
        if (path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        return recycleRoots.stream().anyMatch(normalized::startsWith);
    }

    private Path selectSameVolumeRoot(Path source) throws IOException {
        if (isRecyclePath(source)) {
            throw new IOException("禁止回收回收站自身或其中的对象");
        }
        for (Path root : recycleRoots) {
            if (root.startsWith(source)) {
                throw new IOException("回收站根目录位于源目录内，拒绝执行: " + root);
            }
            ensureRoot(root);
            FileStore sourceStore = Files.getFileStore(source);
            FileStore rootStore = Files.getFileStore(root);
            if (sourceStore.equals(rootStore)) {
                return root;
            }
        }
        throw new IOException("未配置与源路径同一文件系统的回收站根目录，禁止跨卷复制删除: " + source);
    }

    private void ensureRoot(Path root) throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)
                    || hasSymbolicLinkComponent(root)) {
                throw new IOException("回收站根目录不是安全的普通目录: " + root);
            }
            return;
        }
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)
                || hasSymbolicLinkComponent(root)) {
            throw new IOException("创建后的回收站根目录不安全: " + root);
        }
    }

    private static boolean hasSymbolicLinkComponent(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            return false;
        }
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static String createHandle(Path root) throws IOException {
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = UUID.randomUUID().toString().replace("-", "").substring(0, HANDLE_LENGTH);
            try {
                Files.createDirectory(root.resolve(candidate));
                return candidate;
            } catch (FileAlreadyExistsException ignored) {
                // 极低概率碰撞，重新生成；禁止复用既有条目。
            }
        }
        throw new IOException("无法生成不冲突的回收句柄");
    }

    public synchronized boolean restore(String handle) throws IOException {
        requireRealWritesEnabled();
        if (!isSafeHandle(handle)) {
            return false;
        }
        List<HandleLocation> matches = locate(handle);
        if (matches.size() != 1) {
            // 0 = 不存在；>1 = 配置根重叠或人为复制，均不得猜测目标。
            return false;
        }
        HandleLocation location = matches.get(0);
        Path meta = location.meta();
        if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(meta)) {
            return false;
        }
        String content = Files.readString(meta);
        JsonNode node;
        try {
            node = MAPPER.readTree(content);
        } catch (IOException malformed) {
            return false;
        }
        long expiresAt = node.path("expiresAtMs").asLong(0);
        if (expiresAt <= System.currentTimeMillis()) {
            return false;
        }
        String original = node.path("originalPath").asText("");
        String stagedPath = node.path("stagedPath").asText("");
        if (original.isBlank() || stagedPath.isBlank()) {
            return false;
        }
        Path staged = Path.of(stagedPath).toAbsolutePath().normalize();
        Path handleRoot = location.dir().toAbsolutePath().normalize();
        if (!staged.startsWith(handleRoot) || Files.isSymbolicLink(handleRoot)
                || Files.isSymbolicLink(staged) || !Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path target = Path.of(original).toAbsolutePath().normalize();
        if (isRecyclePath(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path parent = target.getParent();
        if (parent == null || hasSymbolicLinkComponent(parent)) {
            return false;
        }
        Path existingParent = nearestExisting(parent);
        if (existingParent == null || !sameFileStore(staged, existingParent)) {
            return false;
        }
        Files.createDirectories(parent);
        Files.move(staged, target);
        deleteHandleAt(location.root(), handle);
        return true;
    }

    private static boolean sameFileStore(Path left, Path right) {
        try {
            return Files.getFileStore(left).equals(Files.getFileStore(right));
        } catch (IOException e) {
            return false;
        }
    }

    private static Path nearestExisting(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        return current;
    }

    private List<HandleLocation> locate(String handle) throws IOException {
        List<HandleLocation> found = new ArrayList<>();
        for (Path root : recycleRoots) {
            Path dir = root.resolve(handle).toAbsolutePath().normalize();
            if (!dir.startsWith(root) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(dir)) {
                continue;
            }
            Path meta = dir.resolve(META_FILE);
            if (Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(meta)) {
                found.add(new HandleLocation(root, dir, meta));
            }
        }
        return found;
    }

    public synchronized List<Map<String, Object>> listEntries() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path root : recycleRoots) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> dirs = Files.list(root)) {
                int scanned = 0;
                var iterator = dirs.iterator();
                while (iterator.hasNext() && scanned++ < maxScanEntries) {
                    Path dir = iterator.next();
                    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)
                            || !isSafeHandle(dir.getFileName().toString())) {
                        continue;
                    }
                    Path meta = dir.resolve(META_FILE);
                    if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(meta)) {
                        continue;
                    }
                    try {
                        JsonNode node = MAPPER.readTree(Files.readString(meta));
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("handle", dir.getFileName().toString());
                        row.put("originalPath", node.path("originalPath").asText(""));
                        row.put("stagedPath", node.path("stagedPath").asText(""));
                        row.put("expiresAtMs", node.path("expiresAtMs").asLong(0));
                        row.put("volumeRoot", root.toString());
                        out.add(row);
                    } catch (IOException malformed) {
                        log.warn("跳过损坏的回收站元数据: {}", meta);
                    }
                }
                if (iterator.hasNext()) {
                    log.warn("回收站目录扫描达到上限 root={} limit={}，剩余条目留待下一次分页扫描", root, maxScanEntries);
                }
            }
        }
        return out;
    }

    public synchronized void purgeExpired() throws IOException {
        requireRealWritesEnabled();
        long now = Instant.now().toEpochMilli();
        for (Path root : recycleRoots) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> dirs = Files.list(root)) {
                int scanned = 0;
                var iterator = dirs.iterator();
                while (iterator.hasNext() && scanned++ < maxScanEntries) {
                    Path dir = iterator.next();
                    String handle = dir.getFileName().toString();
                    if (!isSafeHandle(handle) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(dir)) {
                        continue;
                    }
                    Path meta = dir.resolve(META_FILE);
                    if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(meta)) {
                        continue;
                    }
                    try {
                        long expires = MAPPER.readTree(Files.readString(meta)).path("expiresAtMs").asLong(0);
                        if (expires > 0 && now > expires) {
                            deleteHandleAt(root, handle);
                        }
                    } catch (IOException malformed) {
                        log.warn("保留损坏的回收站条目，等待人工处置: {}", meta);
                    }
                }
                if (iterator.hasNext()) {
                    log.warn("回收站过期清理达到上限 root={} limit={}，剩余条目留待下一次清理", root, maxScanEntries);
                }
            }
        }
    }

    private void deleteHandleAt(Path root, String handle) throws IOException {
        if (!isSafeHandle(handle)) {
            return;
        }
        Path dir = root.resolve(handle).toAbsolutePath().normalize();
        if (!dir.startsWith(root) || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private void writeMeta(Path root, String handle, String original, String staged, String traceId) throws IOException {
        long ttlDays = Math.max(1, opsPepProperties.getRecycleTtlDays());
        long expires = Instant.now().toEpochMilli() + ttlDays * 24L * 3600_000L;
        String json = MAPPER.writeValueAsString(Map.of(
                "originalPath", original,
                "stagedPath", staged,
                "traceId", traceId == null ? "" : traceId,
                "expiresAtMs", expires));
        Files.writeString(root.resolve(handle).resolve(META_FILE), json);
    }

    /** 句柄只能是 moveToRecycleBin 生成的单层安全目录名，阻断路径穿越恢复。 */
    private static boolean isSafeHandle(String handle) {
        return handle != null && handle.matches("[A-Za-z0-9]{" + HANDLE_LENGTH + "}");
    }

    private record HandleLocation(Path root, Path dir, Path meta) {
    }
}
