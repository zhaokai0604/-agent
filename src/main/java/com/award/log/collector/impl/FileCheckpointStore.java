package com.award.log.collector.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Private, atomic, per-source checkpoints. Source content is never stored here. */
final class FileCheckpointStore implements AutoCloseable {
    record Cursor(String source, long position, String fileKey, String anchor, boolean skipLf) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile String error = "";

    FileCheckpointStore(Path base, Path root) {
        FileChannel channel = null;
        FileLock acquired = null;
        try {
            directory = base.toAbsolutePath().normalize().resolve(hash(root.toAbsolutePath().normalize().toString()));
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("Symlink checkpoint directory");
            channel = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Checkpoint already owned");
            lockChannel = channel;
            lock = acquired;
        } catch (Exception failure) {
            try { if (acquired != null) acquired.close(); } catch (IOException ignored) {}
            try { if (channel != null) channel.close(); } catch (IOException ignored) {}
            throw new IllegalStateException("FILE_CHECKPOINT_UNAVAILABLE", failure);
        }
    }

    synchronized Cursor load(Path source) {
        Path file = entry(source);
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.isSymbolicLink(file) || Files.size(file) > 8192) throw new IOException("Invalid checkpoint file");
            Cursor cursor;
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                cursor = JSON.readValue(in.readNBytes(8193), Cursor.class);
            }
            if (cursor == null || !normalized(source).equals(cursor.source()) || cursor.position() < 0
                    || cursor.anchor() == null || !cursor.anchor().matches("[a-f0-9]{64}")) {
                throw new IOException("Invalid checkpoint cursor");
            }
            error = "";
            return cursor;
        } catch (IOException | RuntimeException failure) {
            error = "FILE_CHECKPOINT_INVALID";
            throw new IllegalStateException(error, failure);
        }
    }

    synchronized void save(Cursor cursor) {
        Path temporary = null;
        try {
            if (!lock.isValid()) throw new IOException("Checkpoint store closed");
            Path target = entry(Path.of(cursor.source()));
            temporary = Files.createTempFile(directory, "cursor-", ".tmp");
            byte[] bytes = JSON.writeValueAsBytes(cursor);
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            error = "";
        } catch (IOException | RuntimeException failure) {
            error = "FILE_CHECKPOINT_WRITE_FAILED";
            throw new IllegalStateException(error, failure);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    String error() { return error; }
    private Path entry(Path source) { return directory.resolve(hash(normalized(source)) + ".json"); }
    static String normalized(Path source) { return source.toAbsolutePath().normalize().toString(); }
    static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public synchronized void close() {
        try { if (lock.isValid()) lock.close(); lockChannel.close(); }
        catch (IOException failure) { throw new IllegalStateException("FILE_CHECKPOINT_CLOSE_FAILED", failure); }
    }
}
