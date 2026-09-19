package com.award.log.service.impl;

import com.award.log.model.LogDocument;
import com.award.log.repository.LogDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.domain.PageImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElasticsearchServiceImplTest {

    @Mock
    private LogDocumentRepository logDocumentRepository;
    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    private ElasticsearchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ElasticsearchServiceImpl();
        ReflectionTestUtils.setField(service, "logDocumentRepository", logDocumentRepository);
        ReflectionTestUtils.setField(service, "elasticsearchOperations", elasticsearchOperations);
    }

    @Test
    void indexLogShouldAssignIdAndTimestamp() {
        LogDocument doc = new LogDocument();
        doc.setContent("sample");
        when(logDocumentRepository.save(any(LogDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        LogDocument saved = service.indexLog(doc);
        assertNotNull(saved.getId());
        assertNotNull(saved.getTimestamp());
    }

    @Test
    void bulkIndexLogsShouldReturnCount() {
        LogDocument first = new LogDocument();
        first.setContent("a");
        LogDocument second = new LogDocument();
        second.setContent("b");
        when(logDocumentRepository.saveAll(anyList())).thenReturn(List.of(first, second));

        assertEquals(2, service.bulkIndexLogs(List.of(first, second)));
    }

    @Test
    void getLogByIdShouldReturnDocument() {
        LogDocument doc = new LogDocument();
        doc.setId("id-1");
        when(logDocumentRepository.findById("id-1")).thenReturn(Optional.of(doc));
        assertEquals(doc, service.getLogById("id-1"));
    }

    @Test
    void searchLogsShouldMapHitsToPage() {
        LogDocument doc = new LogDocument();
        doc.setContent("error");
        SearchHit<LogDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(doc);
        @SuppressWarnings("unchecked")
        SearchHits<LogDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(hits.getTotalHits()).thenReturn(1L);
        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class))).thenReturn(hits);

        var page = service.searchLogs("error", PageRequest.of(0, 10));
        assertEquals(1, page.getTotalElements());
        assertEquals("error", page.getContent().get(0).getContent());
    }

    @Test
    void publicQueriesDeletesAndAdvancedSearchDelegateToDependencies() {
        LogDocument doc = new LogDocument();
        doc.setId("id");
        when(logDocumentRepository.findById("missing")).thenReturn(Optional.empty());
        assertNull(service.getLogById("missing"));
        when(logDocumentRepository.findByTimestampBetween(any(), any())).thenReturn(List.of(doc));
        assertEquals(1, service.deleteLogsBefore(LocalDateTime.now()));
        when(logDocumentRepository.findByTaskId("t1")).thenReturn(List.of(doc));
        assertEquals(1, service.deleteLogsByTaskId("t1"));
        var pageable = PageRequest.of(0, 10);
        when(logDocumentRepository.findByTimestampBetween(any(), any(), eq(pageable))).thenReturn(new PageImpl<>(List.of(doc), pageable, 1));
        when(logDocumentRepository.findBySeverity("ERROR", pageable)).thenReturn(new PageImpl<>(List.of(doc), pageable, 1));
        when(logDocumentRepository.findByTaskId("t1", pageable)).thenReturn(new PageImpl<>(List.of(doc), pageable, 1));
        when(logDocumentRepository.findByAnomaly(true, pageable)).thenReturn(new PageImpl<>(List.of(doc), pageable, 1));
        assertEquals(1, service.getLogsByTimeRange(LocalDateTime.MIN, LocalDateTime.MAX, pageable).getTotalElements());
        assertEquals(1, service.getLogsBySeverity("ERROR", pageable).getTotalElements());
        assertEquals(1, service.getLogsByTaskId("t1", pageable).getTotalElements());
        assertEquals(1, service.getAnomalyLogs(pageable).getTotalElements());
        @SuppressWarnings("unchecked")
        SearchHits<LogDocument> hits = mock(SearchHits.class);
        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class))).thenReturn(hits);
        assertSame(hits, service.advancedSearch(mock(Query.class)));
        assertNull(service.getLogStatistics("severity"));
    }

    @Test
    void indexingAndQueryFailuresAreWrapped() {
        LogDocument doc = new LogDocument();
        when(logDocumentRepository.save(any())).thenThrow(new IllegalStateException("es down"));
        assertThrows(RuntimeException.class, () -> service.indexLog(doc));
        when(logDocumentRepository.saveAll(anyList())).thenThrow(new IllegalStateException("es down"));
        assertThrows(RuntimeException.class, () -> service.bulkIndexLogs(List.of(doc)));
        when(logDocumentRepository.findById("x")).thenThrow(new IllegalStateException("es down"));
        assertThrows(RuntimeException.class, () -> service.getLogById("x"));
        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class)))
                .thenThrow(new IllegalStateException("es down"));
        assertThrows(RuntimeException.class, () -> service.searchLogs("x", PageRequest.of(0, 1)));
        assertThrows(RuntimeException.class, () -> service.advancedSearch(mock(Query.class)));
        when(logDocumentRepository.findByTimestampBetween(any(), any(), any())).thenThrow(new IllegalStateException("db"));
        assertThrows(RuntimeException.class, () -> service.getLogsByTimeRange(LocalDateTime.MIN, LocalDateTime.MAX, PageRequest.of(0, 1)));
        when(logDocumentRepository.findBySeverity(anyString(), any())).thenThrow(new IllegalStateException("db"));
        assertThrows(RuntimeException.class, () -> service.getLogsBySeverity("ERROR", PageRequest.of(0, 1)));
        when(logDocumentRepository.findByTaskId(anyString(), any())).thenThrow(new IllegalStateException("db"));
        assertThrows(RuntimeException.class, () -> service.getLogsByTaskId("t", PageRequest.of(0, 1)));
        when(logDocumentRepository.findByAnomaly(anyBoolean(), any())).thenThrow(new IllegalStateException("db"));
        assertThrows(RuntimeException.class, () -> service.getAnomalyLogs(PageRequest.of(0, 1)));
    }
}
