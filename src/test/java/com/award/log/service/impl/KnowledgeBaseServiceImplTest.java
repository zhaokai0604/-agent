package com.award.log.service.impl;

import com.award.log.config.KnowledgeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Contract tests for the V1 knowledge-base fallback and Qdrant boundary. */
class KnowledgeBaseServiceImplTest {

    private KnowledgeProperties properties;
    private KnowledgeBaseServiceImpl service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new KnowledgeProperties();
        properties.setEnabled(true);
        properties.setAllowLocalFallbackEmbedding(true);
        properties.setVectorDimensions(8);
        properties.setChunkSize(900);
        properties.setChunkOverlap(100);
        service = new KnowledgeBaseServiceImpl(properties);
        ReflectionTestUtils.setField(service, "qdrantUrl", "http://qdrant.test");
        ReflectionTestUtils.setField(service, "collectionName", "ops_knowledge");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void disabledKnowledgeBaseFailsClosedWithoutCallingVectorStore() {
        properties.setEnabled(false);
        ReflectionTestUtils.setField(service, "qdrantUrl", "http://127.0.0.1:9");
        server.expect(once(), requestTo("http://127.0.0.1:9/collections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertTrue(service.search("disk cleanup", 5).isEmpty());
        assertEquals(Map.of("seeded", 0, "skipped", true, "reason", "knowledge disabled"),
                service.seedBuiltinIfEmpty());
        assertThrows(IllegalStateException.class, () -> service.upload("title", "content"));
        assertFalse((Boolean) service.getStatus().get("ready"));
    }

    @Test
    void uploadSearchListAndDeleteUseStableQdrantContract() {
        expectCollectionMissing();
        expectPut("/collections/ops_knowledge", "{\"result\":true}");
        expectPut("/collections/ops_knowledge/points?wait=true", "{\"result\":true}");
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge/points/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge/points/scroll"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(scrollPayload(), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant.test/collections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\":{\"points_count\":1,\"config\":{\"params\":{\"vectors\":{\"size\":8}}}}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge/points/delete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("p1")))
                .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge/points/delete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("doc-1")))
                .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON));

        Map<String, Object> uploaded = service.upload("Disk Runbook", "disk pressure cleanup steps",
                "ops", "test");
        assertEquals("Disk Runbook", uploaded.get("title"));
        assertEquals(1, uploaded.get("chunkCount"));

        List<Map<String, Object>> hits = service.search("disk pressure", 3);
        assertEquals(1, hits.size());
        assertEquals("Disk Runbook", hits.get(0).get("title"));

        Map<String, Object> page = service.listDocuments(0, 10);
        assertEquals(1, page.get("total"));
        assertEquals(1, ((List<?>) page.get("list")).size());

        Map<String, Object> status = service.getStatus();
        assertTrue((Boolean) status.get("qdrantConnected"));
        assertTrue((Boolean) status.get("collectionExists"));
        assertEquals(1L, status.get("documentCount"));

        assertTrue(service.delete("p1"));

        assertTrue(service.deleteDocument("doc-1"));
        assertFalse(service.delete(""));
        assertFalse(service.deleteDocument(null));

        server.verify();
    }

    @Test
    void vectorSearchMapsPayloadWhenQdrantReturnsHit() {
        ReflectionTestUtils.setField(service, "collectionReady", true);
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge/points/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":[{\"id\":\"p2\",\"score\":0.91,\"payload\":{"
                        + "\"title\":\"Service Runbook\",\"content\":\"restart safely\","
                        + "\"documentId\":\"doc-2\",\"chunkIndex\":0,\"category\":\"ops\",\"source\":\"test\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> hits = service.search("restart safely", 1);
        assertEquals(1, hits.size());
        assertEquals("p2", hits.get(0).get("id"));
        assertEquals(0.91d, hits.get(0).get("score"));
        server.verify();
    }

    @Test
    void emptyAndUnsupportedUploadsAreRejectedBeforeUpsert() {
        assertThrows(IllegalArgumentException.class, () -> service.upload("title", "   "));
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> service.uploadFile(empty, null, "ops"));
        MockMultipartFile unsupported = new MockMultipartFile("file", "runbook.exe",
                "application/octet-stream", "payload".getBytes());
        assertThrows(IllegalArgumentException.class, () -> service.uploadFile(unsupported, null, "ops"));
    }

    @Test
    void vectorDimensionMismatchRefusesDestructiveAutoMigration() {
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":1024}}}}}",
                        MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upload("title", "content"));
        assertTrue(error.getMessage().contains("向量维度不匹配"));
        server.verify();
    }

    @Test
    void textFileUploadUsesFilenameAsSafeFallbackTitle() {
        ReflectionTestUtils.setField(service, "collectionReady", true);
        expectPut("/collections/ops_knowledge/points?wait=true", "{\"result\":true}");
        MockMultipartFile file = new MockMultipartFile(
                "file", "ops.txt", "text/plain", "safe cleanup runbook".getBytes());

        List<Map<String, Object>> uploaded = service.uploadFile(file, "", "ops");
        assertEquals(1, uploaded.size());
        assertEquals("ops.txt", uploaded.get(0).get("title"));
        server.verify();
    }

    @Test
    void statusReportsUnavailableAndReachableWithoutCollection() {
        server.expect(once(), requestTo("http://qdrant.test/collections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo("http://qdrant.test/collections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo("http://qdrant.test/collections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Map<String, Object> unavailable = service.getStatus();
        assertFalse((Boolean) unavailable.get("qdrantConnected"));
        assertTrue(String.valueOf(unavailable.get("probeHint")).contains("无法连接"));

        ReflectionTestUtils.setField(service, "qdrantApiKey", "secret");
        Map<String, Object> cloudUnavailable = service.getStatus();
        assertTrue((Boolean) cloudUnavailable.get("apiKeyConfigured"));
        assertTrue(String.valueOf(cloudUnavailable.get("probeHint")).contains("API Key"));

        Map<String, Object> noCollection = service.getStatus();
        assertTrue((Boolean) noCollection.get("qdrantConnected"));
        assertFalse((Boolean) noCollection.get("collectionExists"));
        assertTrue(String.valueOf(noCollection.get("probeHint")).contains("首次上传"));
        server.verify();
    }

    @Test
    void listDocumentsSkipsBlankChunksAndSupportsPaginationAndScrollOffsets() {
        ReflectionTestUtils.setField(service, "collectionReady", true);
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge/points/scroll"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":{\"points\":["
                        + "{\"id\":\"p1\",\"payload\":{\"title\":\"old\",\"content\":\"a\",\"documentId\":\"doc-1\",\"category\":\"ops\",\"source\":\"test\",\"createdAt\":\"2026-01-01\"}},"
                        + "{\"id\":\"p2\",\"payload\":{\"title\":\"new\",\"content\":\"b\",\"documentId\":\"doc-2\",\"category\":\"ops\",\"source\":\"test\",\"createdAt\":\"2026-02-01\"}},"
                        + "{\"id\":\"p3\",\"payload\":{\"title\":\"blank\",\"content\":\"ignored\",\"documentId\":\"\"}}"
                        + "]}}", MediaType.APPLICATION_JSON));

        Map<String, Object> page = service.listDocuments(2, 1);
        assertEquals(2, page.get("total"));
        assertEquals(2, page.get("page"));
        assertEquals("doc-1", ((Map<?, ?>) ((List<?>) page.get("list")).get(0)).get("documentId"));
        Map<String, Object> outOfRange = service.listDocuments(9, 1);
        assertTrue(((List<?>) outOfRange.get("list")).isEmpty());
        server.verify();

        KnowledgeBaseServiceImpl paged = new KnowledgeBaseServiceImpl(properties);
        ReflectionTestUtils.setField(paged, "qdrantUrl", "http://qdrant.test");
        ReflectionTestUtils.setField(paged, "collectionName", "ops_knowledge");
        RestTemplate pagedTemplate = (RestTemplate) ReflectionTestUtils.getField(paged, "restTemplate");
        MockRestServiceServer pagedServer = MockRestServiceServer.createServer(pagedTemplate);
        ReflectionTestUtils.setField(paged, "collectionReady", true);
        pagedServer.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge/points/scroll"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":{\"points\":[{\"id\":\"p1\",\"payload\":{\"documentId\":\"doc-1\"}}],\"next_page_offset\":7}}",
                        MediaType.APPLICATION_JSON));
        pagedServer.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge/points/scroll"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":{\"points\":[],\"next_page_offset\":null}}",
                        MediaType.APPLICATION_JSON));
        Map<String, Object> second = paged.listDocuments(1, 10);
        assertEquals(1, second.get("total"));
        pagedServer.verify();
    }

    @Test
    void extractionTransportAndUrlHelpersCoverFallbackBoundaries() throws Exception {
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant.test/bad"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(once(), requestTo("http://qdrant.test/empty"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        MockMultipartFile markdown = new MockMultipartFile(
                "file", "ops.md", "text/markdown", "# Runbook\n\nrestart safely".getBytes());
        assertThrows(IllegalStateException.class, () -> service.uploadFile(markdown, null, "ops"));
        assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(
                service, "readPdf", "not a pdf".getBytes()));

        ReflectionTestUtils.invokeMethod(service, "deleteJson", "/collections/ops_knowledge");

        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "post", "/bad", Map.of("x", 1)));

        JsonNode empty = ReflectionTestUtils.invokeMethod(service, "put", "/empty", Map.of("x", 1));
        assertNotNull(empty);
        assertTrue(empty.isObject());
        assertEquals("http://qdrant.test", ReflectionTestUtils.invokeMethod(
                KnowledgeBaseServiceImpl.class, "normalizeQdrantUrl", "http://qdrant.test///"));
        assertEquals("https://cloud.qdrant.io", ReflectionTestUtils.invokeMethod(
                KnowledgeBaseServiceImpl.class, "normalizeQdrantUrl", "https://cloud.qdrant.io/"));
        assertEquals("http://cloud.qdrant.io:6333", ReflectionTestUtils.invokeMethod(
                KnowledgeBaseServiceImpl.class, "normalizeQdrantUrl", "http://cloud.qdrant.io/"));
        assertEquals("http://localhost:6333", ReflectionTestUtils.invokeMethod(
                KnowledgeBaseServiceImpl.class, "normalizeQdrantUrl", " "));
        server.verify();
    }

    @Test
    void collectionProbeCacheAndEmbeddingFallbackAreObservable() {
        ReflectionTestUtils.setField(service, "collectionReady", false);
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isCollectionPresent"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isCollectionPresent"));

        float[] vector = ReflectionTestUtils.invokeMethod(service, "embed", "disk cleanup");
        assertEquals(8, vector.length);
        boolean nonZero = false;
        for (float value : vector) {
            nonZero |= value != 0;
        }
        assertTrue(nonZero);
        assertEquals("local-fallback", ReflectionTestUtils.invokeMethod(service, "resolveEmbeddingMode"));
        assertEquals(8, ((List<?>) ReflectionTestUtils.invokeMethod(service, "toDoubleList", vector)).size());
        server.verify();
    }

    private void expectCollectionMissing() {
        server.expect(once(), requestTo("http://qdrant.test/collections/ops_knowledge"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
    }

    private void expectPut(String path, String body) {
        server.expect(once(), requestTo("http://qdrant.test" + path))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String scrollPayload() {
        return "{\"result\":{\"points\":[{\"id\":\"p1\",\"payload\":{"
                + "\"title\":\"Disk Runbook\",\"content\":\"disk pressure cleanup steps\","
                + "\"documentId\":\"doc-1\",\"category\":\"ops\",\"source\":\"test\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\"}}],\"next_page_offset\":null}}";
    }
}
