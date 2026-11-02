package com.award.log.collector.impl;

import com.award.log.collector.model.RawLogEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaAcknowledgementTest {
    @SuppressWarnings("unchecked")
    @Test void rejectsFireAndForgetOrLeaderOnlyReceiptConfiguration() {
        KafkaTemplate<String,String> template=mock(KafkaTemplate.class);
        org.springframework.kafka.core.ProducerFactory<String,String> factory=mock(org.springframework.kafka.core.ProducerFactory.class);
        when(template.getProducerFactory()).thenReturn(factory);
        var producer=new KafkaLogProducer();
        ReflectionTestUtils.setField(producer,"kafkaTemplate",template);
        for(Object value:List.of("0", "1", "", "all", -1)) {
            when(factory.getConfigurationProperties()).thenReturn(java.util.Map.of("acks",value));
            if (value.equals("all") || value.equals(-1)) assertDoesNotThrow(producer::validateAcknowledgements);
            else assertThrows(IllegalStateException.class,producer::validateAcknowledgements);
        }
        when(factory.getConfigurationProperties()).thenReturn(java.util.Map.of());
        assertThrows(IllegalStateException.class,producer::validateAcknowledgements);
    }
    @SuppressWarnings("unchecked")
    @Test void waitsForAllBrokerReceiptsAndPropagatesAsyncFailure() {
        KafkaTemplate<String,String> template=mock(KafkaTemplate.class);
        var one=new CompletableFuture<SendResult<String,String>>();
        var two=new CompletableFuture<SendResult<String,String>>();
        when(template.send(eq("raw"),anyString())).thenReturn(one,two);
        var producer=new KafkaLogProducer();
        ReflectionTestUtils.setField(producer,"kafkaTemplate",template);
        ReflectionTestUtils.setField(producer,"logTopic","raw");
        var result=producer.sendRawEventsAcknowledged(List.of(new RawLogEvent(),new RawLogEvent()));
        one.complete(null); assertFalse(result.isDone());
        two.completeExceptionally(new IllegalStateException("broker refused"));
        assertThrows(java.util.concurrent.CompletionException.class,result::join);
    }
    @SuppressWarnings("unchecked")
    @Test void synchronousSendFailureIsReturnedAsFailedFutureAndEmptyBatchSucceeds() {
        KafkaTemplate<String,String> template=mock(KafkaTemplate.class);
        when(template.send(eq("raw"),anyString())).thenThrow(new IllegalStateException("no broker"));
        var producer=new KafkaLogProducer();
        ReflectionTestUtils.setField(producer,"kafkaTemplate",template);
        ReflectionTestUtils.setField(producer,"logTopic","raw");
        assertDoesNotThrow(() -> producer.sendRawEventsAcknowledged(List.of()).join());
        assertTrue(producer.sendRawEventsAcknowledged(List.of(new RawLogEvent())).isCompletedExceptionally());
    }
}
