package com.award.log.collector;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LogCollectorManagerTest {

    @Test void stopFailureDoesNotPreventOtherCollectorsFromClosing() {
        var broken = mock(LogCollector.class); var other = mock(LogCollector.class);
        when(broken.getName()).thenReturn("broken"); when(other.getName()).thenReturn("other");
        doThrow(new IllegalStateException("fixture failure")).when(broken).stop();
        var manager = new LogCollectorManager(); manager.addCollector(broken); manager.addCollector(other);
        assertThrows(IllegalStateException.class, manager::stopAll);
        verify(other).stop(); verify(broken).stop();
    }

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

    @Test void fanOutByteLimitDropsOldLinesAndRejectsAnOversizedLine() {
        LogCollectorManager manager = new LogCollectorManager();
        String line = "界".repeat(1024 * 1024);
        manager.publishFanOut(List.of(line, line, line, line, line, line, line, line, line));
        assertTrue(manager.fanOutBytes() <= LogCollectorManager.FAN_OUT_MAX_BYTES);
        assertTrue(manager.fanOutSize() < 9);
        assertTrue(manager.fanOutDroppedLines() > 0);
        manager.takeFanOut(2);
        assertTrue(manager.fanOutBytes() <= LogCollectorManager.FAN_OUT_MAX_BYTES);
        manager.publishFanOut(List.of("x".repeat(9 * 1024 * 1024)));
        assertTrue(manager.fanOutDroppedLines() > 0);
        assertTrue(manager.fanOutBytes() <= LogCollectorManager.FAN_OUT_MAX_BYTES);
    }
}
