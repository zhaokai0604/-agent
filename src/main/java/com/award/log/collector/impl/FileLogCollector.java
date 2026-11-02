package com.award.log.collector.impl;

import com.award.log.collector.LogCollector;
import com.award.log.collector.CollectedLogBatch;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/**
 * 文件/目录日志采集器：WatchService 或轮询增量读。
 * UTF-8 增量采集，未完成行暂存；缓冲满时保留游标和当前行，等待排水。
 */
@Slf4j
public class FileLogCollector implements LogCollector {

    private static final int DEFAULT_BUFFER_MAX_LINES = 8000;
    private static final int MAX_LINE_BYTES = 64 * 1024;
    private static final int MAX_READ_BYTES_PER_TICK = 1024 * 1024;
    private static final int MAX_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final class TailState {
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        Object fileKey;
        String anchor;
        boolean oversized;
        boolean skipLf;
        FileCheckpointStore.Cursor completed;
    }

    private final String filePath;
    private final String name;
    private final Set<String> includeExtensions;
    private final Set<String> excludeDirectories;
    private final int maxDepth;
    private final int pollIntervalMs;
    private final int bufferMaxLines;
    private final boolean startAtEof;
    private final FileCheckpointStore checkpoints;
    private volatile RuntimeException startupFailure;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService;
    private final List<String> collectedLogs;
    private final Map<String, Long> fileReadPositions = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> watchKeyPathMap = new ConcurrentHashMap<>();
    private volatile CountDownLatch startupLatch = new CountDownLatch(0);
    private final Map<String, TailState> tails = new ConcurrentHashMap<>();
    private long bufferedBytes;
    private final java.util.concurrent.atomic.AtomicLong backpressureEvents = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong oversizedLines = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong invalidUtf8Lines = new java.util.concurrent.atomic.AtomicLong();

    public FileLogCollector(String filePath, String name) {
        this(filePath, name,
                ".log,.txt,.out,.err,.debug,.info,.warn,.error,.fatal,.access,.audit,.trace",
                "node_modules,target,.git,.idea,.vscode,.cursor");
    }

    public FileLogCollector(String filePath, String name, String includeExtensions, String excludeDirectories) {
        this(filePath, name, includeExtensions, excludeDirectories, 4, 5000,
                DEFAULT_BUFFER_MAX_LINES, true);
    }

    public FileLogCollector(String filePath, String name, String includeExtensions, String excludeDirectories,
                            int maxDepth, int pollIntervalMs) {
        this(filePath, name, includeExtensions, excludeDirectories, maxDepth, pollIntervalMs,
                DEFAULT_BUFFER_MAX_LINES, true);
    }

    public FileLogCollector(String filePath, String name, String includeExtensions, String excludeDirectories,
                            int maxDepth, int pollIntervalMs, int bufferMaxLines, boolean startAtEof) {
        this(filePath, name, includeExtensions, excludeDirectories, maxDepth, pollIntervalMs,
                bufferMaxLines, startAtEof, null);
    }

    public FileLogCollector(String filePath, String name, String includeExtensions, String excludeDirectories,
                            int maxDepth, int pollIntervalMs, int bufferMaxLines, boolean startAtEof,
                            Path checkpointDirectory) {
        this.filePath = filePath;
        this.name = name;
        this.maxDepth = Math.max(1, maxDepth);
        this.pollIntervalMs = Math.max(1000, pollIntervalMs);
        this.bufferMaxLines = Math.max(100, bufferMaxLines);
        this.startAtEof = startAtEof;
        this.checkpoints = checkpointDirectory == null ? null : new FileCheckpointStore(checkpointDirectory, Path.of(filePath));
        this.executorService = Executors.newSingleThreadExecutor();
        this.collectedLogs = new ArrayList<>();
        this.includeExtensions = parseCsvToLowercaseSet(includeExtensions);
        this.excludeDirectories = parseCsvToLowercaseSet(excludeDirectories);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[文件日志采集器] 开始采集，文件路径: {}", filePath);
            CountDownLatch ready = new CountDownLatch(1);
            startupLatch = ready;
            executorService.submit(() -> {
                try { collectLogsContinuously(); }
                catch (RuntimeException failure) { startupFailure = failure; running.set(false); }
                finally { markStartupReady(); }
            });
            try {
                // start() returns only after the initial EOF seed/watch registration is ready.
                // This closes the race where a fast append is mistaken for pre-existing data.
                ready.await(Math.max(1000L, Math.min(5000L, pollIntervalMs + 1000L)), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (startupFailure != null) throw startupFailure;
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("[文件日志采集器] 停止采集，文件路径: {}", filePath);
        }
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("FILE_COLLECTOR_STOP_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FILE_COLLECTOR_STOP_INTERRUPTED", interrupted);
        }
        if (checkpoints != null) checkpoints.close();
    }

    @Override
    public List<String> collect() {
        return collectBatch().lines();
    }

    @Override public CollectedLogBatch collectBatch() {
        synchronized (collectedLogs) {
            List<String> logs = new ArrayList<>(collectedLogs);
            List<FileCheckpointStore.Cursor> cursors = tails.values().stream()
                    .map(t -> t.completed).filter(java.util.Objects::nonNull).toList();
            collectedLogs.clear();
            bufferedBytes = 0;
            return new CollectedLogBatch(logs, () -> {
                if (checkpoints != null) cursors.forEach(checkpoints::save);
            });
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void collectLogsContinuously() {
        Path root = Paths.get(filePath);
        if (Files.notExists(root)) {
            log.warn("[文件日志采集器] 文件或目录不存在: {}", filePath);
            markStartupReady();
            return;
        }
        if (Files.isDirectory(root)) {
            watchDirectoryRecursively(root);
        } else {
            pollSingleFile(root);
        }
    }

    private void watchDirectoryRecursively(Path scanRoot) {
        this.root = scanRoot;
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            if (startAtEof) {
                seedEofPositions(scanRoot, 0);
            } else {
                collectFromDirectory(scanRoot, 0);
            }
            registerAllDirectories(scanRoot, watchService, 0);
            markStartupReady();
            while (running.get()) {
                WatchKey key = watchService.poll(pollIntervalMs, TimeUnit.MILLISECONDS);
                if (key == null) {
                    collectFromDirectory(scanRoot, 0);
                    continue;
                }
                processWatchKey(key, watchService);
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException invalid && invalid.getMessage().startsWith("FILE_CHECKPOINT")) throw invalid;
            if (!running.get()) return;
            log.error("[文件日志采集器] WatchService异常，回退轮询: {}", e.getMessage());
            fallbackDirectoryPolling(scanRoot);
        }
    }

    private void fallbackDirectoryPolling(Path scanRoot) {
        this.root = scanRoot;
        if (startAtEof && fileReadPositions.isEmpty()) {
            try {
                seedEofPositions(scanRoot, 0);
            } catch (Exception e) {
                if (e instanceof IllegalStateException invalid) throw invalid;
                log.debug("[文件日志采集器] EOF 种子失败: {}", e.getMessage());
            }
        }
        markStartupReady();
        while (running.get()) {
            try {
                collectFromDirectory(scanRoot, 0);
                Thread.sleep(pollIntervalMs);
            } catch (Exception ex) {
                if (!running.get()) return;
                log.warn("[文件日志采集器] 回退轮询异常: {}", ex.getMessage());
                sleepQuietly(3000);
            }
        }
    }

    private void pollSingleFile(Path file) {
        if (!fileReadPositions.containsKey(FileCheckpointStore.normalized(file))) {
            try {
                initializePosition(file, startAtEof);
            } catch (Exception e) {
                if (e instanceof IllegalStateException invalid) throw invalid;
                log.debug("[文件日志采集器] 单文件 EOF 种子失败: {}", e.getMessage());
            }
        }
        markStartupReady();
        while (running.get()) {
            try {
                collectFromFile(file);
                Thread.sleep(pollIntervalMs);
            } catch (Exception e) {
                if (!running.get()) return;
                log.warn("[文件日志采集器] 文件轮询异常: {}", e.getMessage());
                sleepQuietly(3000);
            }
        }
    }

    private void processWatchKey(WatchKey key, WatchService watchService) throws IOException {
        Path dir = watchKeyPathMap.get(key);
        if (dir == null) {
            key.reset();
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                collectFromDirectory(root, 0);
                continue;
            }
            Path changedRelativePath = (Path) event.context();
            Path changedPath = dir.resolve(changedRelativePath);
            if (Files.isDirectory(changedPath) && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                registerAllDirectories(changedPath, watchService, depthOf(dir));
                continue;
            }
            if (Files.isRegularFile(changedPath) && isLogFile(changedPath)) {
                collectFromFile(changedPath);
            }
        }
        boolean valid = key.reset();
        if (!valid) {
            watchKeyPathMap.remove(key);
        }
    }

    private int depthOf(Path directory) {
        try {
            return rootDepth(root, directory);
        } catch (Exception e) {
            return maxDepth;
        }
    }

    private int rootDepth(Path root, Path dir) {
        if (dir == null || dir.equals(root)) {
            return 0;
        }
        Path parent = dir.getParent();
        if (parent == null || parent.equals(dir)) {
            return 1;
        }
        return 1 + rootDepth(root, parent);
    }

    private Path root;

    private void registerAllDirectories(Path scanRoot, WatchService watchService, int depth) {
        if (depth >= maxDepth) {
            return;
        }
        try (var directories = Files.walk(scanRoot, Math.max(1, maxDepth - depth))) {
            directories
                    .filter(Files::isDirectory)
                    .filter(this::isAllowedDirectory)
                    .forEach(path -> registerDirectory(path, watchService));
        } catch (Exception e) {
            log.debug("[文件日志采集器] 注册监视目录失败: {}", scanRoot, e);
        }
    }

    private void registerDirectory(Path directory, WatchService watchService) {
        try {
            WatchKey key = directory.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchKeyPathMap.put(key, directory);
        } catch (IOException e) {
            log.debug("[文件日志采集器] 目录注册失败: {}", directory, e);
        }
    }

    private void collectFromFile(Path path) throws IOException {
        synchronized (collectedLogs) { collectFromFileLocked(path); }
    }

    private void initializePosition(Path path, boolean seedAtEof) throws IOException {
        synchronized (collectedLogs) {
            String source = FileCheckpointStore.normalized(path);
            if (fileReadPositions.containsKey(source)) return;
            TailState state = tails.computeIfAbsent(source, ignored -> new TailState());
            FileCheckpointStore.Cursor cursor = checkpoints == null ? null : checkpoints.load(path);
            if (cursor == null) {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                long position = seedAtEof ? attrs.size() : 0;
                try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                    boolean skipLf = false;
                    if (position > 0) { file.seek(position - 1); skipLf = file.read() == '\r'; }
                    cursor = new FileCheckpointStore.Cursor(source, position,
                            attrs.fileKey() == null ? null : attrs.fileKey().toString(), cursorAnchor(file, position), skipLf);
                }
                if (checkpoints != null) checkpoints.save(cursor);
            }
            state.fileKey = cursor.fileKey(); state.anchor = cursor.anchor(); state.skipLf = cursor.skipLf();
            state.completed = cursor;
            fileReadPositions.put(source, cursor.position());
        }
    }

    private void collectFromFileLocked(Path path) throws IOException {
        initializePosition(path, false);
        String fileName = FileCheckpointStore.normalized(path);
        TailState state = tails.computeIfAbsent(fileName, ignored -> new TailState());
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        long position = fileReadPositions.getOrDefault(fileName, 0L);
        if (position > attributes.size() || state.fileKey != null && attributes.fileKey() != null
                && !state.fileKey.toString().equals(attributes.fileKey().toString())) {
            position = 0;
            state.pending.reset(); state.oversized = false; state.skipLf = false;
        }
        state.fileKey = attributes.fileKey();
        int completed = 0;
        int consumed = 0;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (position > file.length() || state.anchor != null
                    && !state.anchor.equals(cursorAnchor(file, position))) {
                position = 0;
                state.pending.reset(); state.oversized = false; state.skipLf = false;
            }
            try {
                file.seek(position);
                byte[] chunk = new byte[8192];
                long end = file.length();
                while (position < end && consumed < MAX_READ_BYTES_PER_TICK && completed < 2000
                        && !Thread.currentThread().isInterrupted()) {
                    int count = file.read(chunk, 0, (int)Math.min(chunk.length,
                            Math.min(end-position, MAX_READ_BYTES_PER_TICK-consumed)));
                    if (count < 0) break;
                    for (int i=0; i<count; i++) {
                        int value = chunk[i] & 0xff;
                        if (state.skipLf) {
                            state.skipLf = false;
                            if (value == '\n') {
                                position++; consumed++;
                                state.completed = cursor(fileName, position, state, file);
                                file.seek(position + count - i - 1);
                                continue;
                            }
                        }
                        if (value == '\n' || value == '\r') {
                            if (!state.oversized) {
                                String line = decodeLine(state.pending.toByteArray(), position-state.pending.size());
                                if (line != null && !appendBufferedLine(line)) {
                                    fileReadPositions.put(fileName, position);
                                    return; // Keep the line and unread delimiter for the next drain.
                                }
                            }
                            state.pending.reset(); state.oversized = false; state.skipLf = value == '\r';
                            state.completed = cursor(fileName, position + 1, state, file);
                            // cursorAnchor seeks; restore the end of the chunk before the next read.
                            file.seek(position + count - i);
                            completed++;
                        } else if (!state.oversized) {
                            if (state.pending.size() == MAX_LINE_BYTES) {
                                state.pending.reset(); state.oversized = true;
                                oversizedLines.incrementAndGet();
                                log.warn("[文件日志采集器] 超长行超过 {} 字节，跳过至下一换行: {}", MAX_LINE_BYTES, fileName);
                            } else state.pending.write(value);
                        }
                        position++; consumed++;
                        if (completed >= 2000) break;
                    }
                }
            } finally {
                fileReadPositions.put(fileName, position);
                state.anchor = cursorAnchor(file, position);
            }
        }
    }

    /** Windows providers may return no fileKey and preserve creation time on replacement. */
    private static String cursorAnchor(RandomAccessFile file, long position) throws IOException {
        if (position > file.length()) return FileCheckpointStore.hash(new byte[0]);
        int size = (int)Math.min(64, position);
        byte[] anchor = new byte[size];
        file.seek(position-size);
        file.readFully(anchor);
        return FileCheckpointStore.hash(anchor);
    }

    private static FileCheckpointStore.Cursor cursor(String source, long position, TailState state,
                                                    RandomAccessFile file) throws IOException {
        return new FileCheckpointStore.Cursor(source, position,
                state.fileKey == null ? null : state.fileKey.toString(), cursorAnchor(file, position), state.skipLf);
    }

    private String decodeLine(byte[] bytes, long offset) {
        try {
            String line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return offset == 0 && line.startsWith("\uFEFF") ? line.substring(1) : line;
        } catch (CharacterCodingException invalid) {
            invalidUtf8Lines.incrementAndGet();
            log.warn("[文件日志采集器] 跳过非法 UTF-8 行，需检查源日志编码: {}", name);
            return null;
        }
    }

    /** Read-only counters; no source log content is exposed. */
    @Override public Map<String, Object> diagnostics() {
        synchronized (collectedLogs) {
            Map<String, Object> result = new java.util.LinkedHashMap<>(Map.of("encoding", "UTF-8", "bufferedLines", collectedLogs.size(),
                    "bufferedBytes", bufferedBytes, "backpressureEvents", backpressureEvents.get(),
                    "oversizedLines", oversizedLines.get(), "invalidUtf8Lines", invalidUtf8Lines.get(),
                    "maxLineBytes", MAX_LINE_BYTES, "maxReadBytesPerTick", MAX_READ_BYTES_PER_TICK,
                    "maxBufferBytes", MAX_BUFFER_BYTES));
            result.put("checkpointEnabled", checkpoints != null);
            result.put("checkpointError", checkpoints == null ? "" : checkpoints.error());
            result.put("checkpointBoundary", "DURABLE_SINK_ACK");
            return result;
        }
    }

    private boolean appendBufferedLine(String line) {
        synchronized (collectedLogs) {
            int bytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (collectedLogs.size() >= bufferMaxLines || bufferedBytes + bytes > MAX_BUFFER_BYTES) {
                long blocked = backpressureEvents.incrementAndGet();
                if (blocked == 1 || blocked % 1000 == 0) {
                    log.warn("[文件日志采集器] 缓冲已满，保留当前行等待排水，背压次数: {}", blocked);
                }
                return false;
            }
            collectedLogs.add(line);
            bufferedBytes += bytes;
            return true;
        }
    }

    /** 启动种子：把已有日志文件位点推到 EOF，只跟增量。 */
    private void seedEofPositions(Path directory, int depth) throws IOException {
        if (!isAllowedDirectory(directory) || depth >= maxDepth) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                try {
                    if (Files.isDirectory(entry)) {
                        seedEofPositions(entry, depth + 1);
                    } else if (Files.isRegularFile(entry) && isLogFile(entry)) {
                        initializePosition(entry, true);
                    }
                } catch (Exception e) {
                    if (e instanceof IllegalStateException invalid) throw invalid;
                    log.debug("[文件日志采集器] EOF 种子跳过: {}", entry, e);
                }
            }
        }
    }

    private void collectFromDirectory(Path directory, int depth) throws IOException {
        if (!isAllowedDirectory(directory) || depth >= maxDepth) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                try {
                    if (Files.isDirectory(entry)) {
                        collectFromDirectory(entry, depth + 1);
                    } else if (Files.isRegularFile(entry) && isLogFile(entry)) {
                        collectFromFile(entry);
                    }
                } catch (Exception e) {
                    if (e instanceof IllegalStateException invalid) throw invalid;
                    log.debug("[文件日志采集器] 无法访问: {}", entry, e);
                }
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException invalid) throw invalid;
            log.debug("[文件日志采集器] 无法访问目录: {}", directory, e);
        }
    }
    
    /**
     * 检查是否为日志文件
     * 支持多种常见的日志文件后缀
     */
    private boolean isLogFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        for (String ext : includeExtensions) {
            if (!ext.isEmpty() && fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedDirectory(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        if (excludeDirectories.contains(name)) {
            return false;
        }
        if (File.separatorChar == '\\') {
            String normalized = path.toAbsolutePath().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (normalized.startsWith("c:/programdata/microsoft/")
                    || normalized.startsWith("c:/programdata/windows defender")
                    || normalized.startsWith("c:/$recycle.bin")
                    || normalized.startsWith("c:/system volume information")) {
                return false;
            }
        }
        return true;
    }

    private Set<String> parseCsvToLowercaseSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return new HashSet<>();
        }
        Set<String> result = new HashSet<>();
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .forEach(result::add);
        return result;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void markStartupReady() {
        startupLatch.countDown();
    }
}
