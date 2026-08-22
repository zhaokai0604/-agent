package com.award.log.collector;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogCollectorManagerTest {

    @Test
    void publishFanOutShouldBoundQueueAndDropOldest() {
        LogCollectorManager manager = new LogCollectorManager();
        List<String> lines = new ArrayList<>();
        int overflow = 17;
        for (int i = 0; i < LogCollectorManager.FAN_OUT_CAPACITY + overflow; i++) {
            lines.add("line-" + i);
        }
        manager.publishFanOut(lines);
        assertEquals(LogCollectorManager.FAN_OUT_CAPACITY, manager.fanOutSize());

        List<String> taken = manager.takeFanOut(8);
        assertEquals(8, taken.size());
        assertEquals("line-" + overflow, taken.get(0));
        assertEquals(LogCollectorManager.FAN_OUT_CAPACITY - 8, manager.fanOutSize());
    }

    @Test
    void snapshotFanOutShouldNotDrainQueue() {
        LogCollectorManager manager = new LogCollectorManager();
        manager.publishFanOut(List.of("a", "b", "c"));
        List<String> snap = manager.snapshotFanOut(2);
        assertEquals(List.of("a", "b"), snap);
        assertEquals(3, manager.fanOutSize());
        assertEquals(List.of("a", "b", "c"), manager.takeFanOut(10));
        assertTrue(manager.takeFanOut().isEmpty());
    }
}
