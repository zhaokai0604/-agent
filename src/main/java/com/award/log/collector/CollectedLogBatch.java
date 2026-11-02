package com.award.log.collector;

import java.util.List;

/** Acknowledgement means acceptance by a durable sink, not merely an in-memory queue. */
public record CollectedLogBatch(List<String> lines, Runnable acknowledge) {
    public CollectedLogBatch {
        lines = List.copyOf(lines);
        java.util.Objects.requireNonNull(acknowledge);
    }
}
