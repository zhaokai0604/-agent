package com.award.log.task;

import java.util.concurrent.Callable;

/** Carries an execution fencing token across analysis and shard worker threads. */
public final class TaskExecutionContext implements AutoCloseable {
    public record Token(String taskId, String executionId) {}
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private final Token previous;

    private TaskExecutionContext(Token token) { previous = CURRENT.get(); CURRENT.set(token); }
    public static TaskExecutionContext open(String taskId, String executionId) {
        return new TaskExecutionContext(new Token(taskId, executionId));
    }
    public static Token current() { return CURRENT.get(); }
    public static <T> Callable<T> wrap(Callable<T> task) {
        Token captured = CURRENT.get();
        return () -> {
            try (var ignored = new TaskExecutionContext(captured)) { return task.call(); }
        };
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
