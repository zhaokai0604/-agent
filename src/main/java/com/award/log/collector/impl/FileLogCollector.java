package com.award.log.collector.impl;

import com.award.log.collector.LogCollector;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/**
 * 文件日志采集器
 * 支持从本地文件或目录中采集日志
 */
@Slf4j
public class FileLogCollector implements LogCollector {

    private final String filePath;
    private final String name;
    private final Set<String> includeExtensions;
    private final Set<String> excludeDirectories;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService;
    private final List<String> collectedLogs;
    private final Map<String, Long> fileReadPositions = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> watchKeyPathMap = new ConcurrentHashMap<>();

    public FileLogCollector(String filePath, String name) {
        this(filePath, name,
                ".log,.txt,.out,.err,.debug,.info,.warn,.error,.fatal,.access,.audit,.trace",
                "node_modules,target,.git,.idea,.vscode,.cursor");
    }

    public FileLogCollector(String filePath, String name, String includeExtensions, String excludeDirectories) {
        this.filePath = filePath;
        this.name = name;
        this.executorService = Executors.newSingleThreadExecutor();
        this.collectedLogs = new ArrayList<>();
        this.includeExtensions = parseCsvToLowercaseSet(includeExtensions);
        this.excludeDirectories = parseCsvToLowercaseSet(excludeDirectories);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[文件日志采集器] 开始采集，文件路径: {}", filePath);
            executorService.submit(this::collectLogsContinuously);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("[文件日志采集器] 停止采集，文件路径: {}", filePath);
            executorService.shutdown();
        }
    }

    @Override
    public List<String> collect() {
        synchronized (collectedLogs) {
            List<String> logs = new ArrayList<>(collectedLogs);
            collectedLogs.clear();
            return logs;
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
            return;
        }
        if (Files.isDirectory(root)) {
            watchDirectoryRecursively(root);
        } else {
            pollSingleFile(root);
        }
    }

    private void watchDirectoryRecursively(Path root) {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            collectFromDirectory(root);
            registerAllDirectories(root, watchService);
            while (running.get()) {
                WatchKey key = watchService.poll(1500, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
                processWatchKey(key, watchService);
            }
        } catch (Exception e) {
            log.error("[文件日志采集器] WatchService异常，回退轮询: {}", e.getMessage());
            fallbackDirectoryPolling(root);
        }
    }

    private void fallbackDirectoryPolling(Path root) {
        while (running.get()) {
            try {
                collectFromDirectory(root);
                Thread.sleep(2000);
            } catch (Exception ex) {
                log.warn("[文件日志采集器] 回退轮询异常: {}", ex.getMessage());
                sleepQuietly(3000);
            }
        }
    }

    private void pollSingleFile(Path file) {
        while (running.get()) {
            try {
                collectFromFile(file);
                Thread.sleep(1000);
            } catch (Exception e) {
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
                continue;
            }
            Path changedRelativePath = (Path) event.context();
            Path changedPath = dir.resolve(changedRelativePath);
            if (Files.isDirectory(changedPath) && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                registerAllDirectories(changedPath, watchService);
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

    private void registerAllDirectories(Path root, WatchService watchService) throws IOException {
        Files.walk(root)
                .filter(Files::isDirectory)
                .filter(this::isAllowedDirectory)
                .forEach(path -> registerDirectory(path, watchService));
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
        String fileName = path.toAbsolutePath().toString();
        long lastReadPosition = fileReadPositions.getOrDefault(fileName, 0L);
        
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (lastReadPosition > file.length()) {
                // 处理日志滚动/截断场景
                lastReadPosition = 0L;
            }
            file.seek(lastReadPosition);
            String line;
            while ((line = file.readLine()) != null) {
                synchronized (collectedLogs) {
                    collectedLogs.add(line);
                }
            }
            long newPosition = file.getFilePointer();
            fileReadPositions.put(fileName, newPosition);
        }
    }

    private void collectFromDirectory(Path directory) throws IOException {
        if (!isAllowedDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                try {
                    if (Files.isDirectory(entry)) {
                        collectFromDirectory(entry);
                    } else if (Files.isRegularFile(entry) && isLogFile(entry)) {
                        collectFromFile(entry);
                    }
                } catch (Exception e) {
                    log.debug("[文件日志采集器] 无法访问: {}", entry, e);
                }
            }
        } catch (Exception e) {
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
        return !excludeDirectories.contains(name);
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
}
