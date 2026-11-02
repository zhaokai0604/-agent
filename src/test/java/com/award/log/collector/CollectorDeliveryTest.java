package com.award.log.collector;

import com.award.log.collector.impl.KafkaLogProducer;
import com.award.log.collector.model.RawLogEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectorDeliveryTest {
    static class Source implements LogCollector {
        List<String> lines = List.of("ERROR evidence");
        int acknowledged;
        boolean failAck;
        public void start() {}
        public void stop() {}
        public boolean isRunning() { return true; }
        public String getName() { return "source"; }
        public List<String> collect() { var result=lines; lines=List.of(); return result; }
        public CollectedLogBatch collectBatch() {
            return new CollectedLogBatch(collect(), () -> {
                if (failAck) throw new IllegalStateException("disk full");
                acknowledged++;
            });
        }
    }
    CollectorDispatchScheduler scheduler(LogCollectorManager manager, KafkaLogProducer kafka) {
        var scheduler = new CollectorDispatchScheduler(manager, kafka);
        ReflectionTestUtils.setField(scheduler,"dispatchEnabled",true);
        ReflectionTestUtils.setField(scheduler,"sourceId","fixture");
        return scheduler;
    }
    @Test void incompleteDeliveryDoesNotAdvanceCursorOrDrainNextBatch() {
        var manager = new LogCollectorManager(); var source = new Source(); manager.addCollector(source);
        var kafka = mock(KafkaLogProducer.class); var future = new CompletableFuture<Void>();
        when(kafka.sendRawEventsAcknowledged(anyList())).thenReturn(future);
        var scheduler = scheduler(manager, kafka);
        scheduler.dispatch(); source.lines = List.of("next"); scheduler.dispatch();
        assertEquals(0, source.acknowledged);
        assertEquals(1,scheduler.snapshot().get("pendingDeliveryLines"));
        assertEquals(List.of("next"),source.lines);
        assertEquals(0L,scheduler.snapshot().get("totalDispatched"));
        verify(kafka,times(1)).sendRawEventsAcknowledged(anyList());
        future.complete(null); scheduler.dispatch();
        assertEquals(1,source.acknowledged);
        assertEquals(1L,scheduler.snapshot().get("totalDispatched"));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void failedSendRetriesSameEventsAndAcknowledgesOnlyAfterReceipt() {
        var manager = new LogCollectorManager(); var source = new Source(); manager.addCollector(source);
        var kafka = mock(KafkaLogProducer.class);
        when(kafka.sendRawEventsAcknowledged(anyList())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("unavailable")), CompletableFuture.completedFuture(null));
        var scheduler = scheduler(manager,kafka);
        scheduler.dispatch(); assertEquals(0,source.acknowledged);
        assertEquals(1L,scheduler.snapshot().get("dispatchFailures"));
        scheduler.dispatch(); assertEquals(1,source.acknowledged);
        ArgumentCaptor<List<RawLogEvent>> captor=ArgumentCaptor.forClass((Class)List.class);
        verify(kafka,times(2)).sendRawEventsAcknowledged(captor.capture());
        assertSame(captor.getAllValues().get(0),captor.getAllValues().get(1));
        assertEquals("ERROR",captor.getValue().get(0).getLevel());
        assertEquals(List.of("ERROR evidence"),manager.takeFanOut());
        assertEquals(0,scheduler.snapshot().get("pendingDeliveryLines"));
    }
    @Test void checkpointFailureRetainsBatchForRetry() {
        var manager = new LogCollectorManager(); var source = new Source(); source.failAck=true; manager.addCollector(source);
        var kafka=mock(KafkaLogProducer.class);
        when(kafka.sendRawEventsAcknowledged(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        var scheduler=scheduler(manager,kafka); scheduler.dispatch();
        assertEquals(0L,scheduler.snapshot().get("totalDispatched"));
        assertEquals(1,scheduler.snapshot().get("pendingDeliveryLines"));
        source.failAck=false; scheduler.dispatch();
        assertEquals(1,source.acknowledged);
    }
    @Test void localMemoryFanoutDoesNotAcknowledgeDurabilityAndDisabledDispatchDoesNotDrain() {
        var manager=new LogCollectorManager();var source=new Source();manager.addCollector(source);
        var scheduler=scheduler(manager,null);
        ReflectionTestUtils.setField(scheduler,"dispatchEnabled",false); scheduler.dispatch();
        assertFalse(source.lines.isEmpty());
        ReflectionTestUtils.setField(scheduler,"dispatchEnabled",true); scheduler.dispatch();
        assertEquals(0,source.acknowledged);
        assertEquals("NONE_REPLAY_ON_RESTART",scheduler.snapshot().get("checkpointReceiver"));
        assertEquals(List.of("ERROR evidence"),manager.takeFanOut());
        scheduler.dispatch(); assertEquals(0L,scheduler.snapshot().get("lastBatchSize"));
    }
}
