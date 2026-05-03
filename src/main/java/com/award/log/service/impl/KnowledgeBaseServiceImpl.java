package com.award.log.service.impl;

import com.award.log.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    @Value("${qdrant.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${qdrant.collection:ops_knowledge}")
    private String collectionName;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> upload(String title, String content) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ensureCollection();
        List<Double> vector = vectorize(content);
        Map<String, Object> point = Map.of(
                "id", id,
                "vector", vector,
                "payload", Map.of("title", title, "content", content)
        );
        Map<String, Object> body = Map.of("points", List.of(point));
        post("/collections/" + collectionName + "/points", body);
        log.info("[知识库] 知识条目已上传，ID: [{}], 标题: [{}]", id, title);
        return Map.of("id", id, "title", title);
    }

    @Override
    public List<Map<String, Object>> search(String query, int topK) {
        ensureCollection();
        List<Double> queryVector = vectorize(query);
        Map<String, Object> body = Map.of(
                "vector", queryVector,
                "limit", topK,
                "with_payload", true
        );
        JsonNode json = post("/collections/" + collectionName + "/points/search", body);
        List<Map<String, Object>> result = new ArrayList<>();

        if (json != null && json.has("result")) {
            JsonNode arr = json.path("result");
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    result.add(Map.of(
                            "id", item.path("id").asText(),
                            "title", item.path("payload").path("title").asText(),
                            "content", item.path("payload").path("content").asText(),
                            "score", item.path("score").asDouble()
                    ));
                }
            }
        }

        log.info("[知识库] 检索查询完成，查询: [{}], 结果数: [{}]", query, result.size());
        return result;
    }

    @Override
    public boolean delete(String id) {
        ensureCollection();
        post("/collections/" + collectionName + "/points/delete", Map.of("points", List.of(id)));
        log.info("[知识库] 知识条目已删除，ID: [{}]", id);
        return true;
    }

    private List<Double> vectorize(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>(List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        }

        double[] vec = new double[16];
        for (String token : text.toLowerCase().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            int i = Math.abs(token.hashCode()) % vec.length;
            vec[i] += 1.0;
        }

        double norm = 0.0;
        for (double v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }

        List<Double> result = new ArrayList<>(vec.length);
        for (double v : vec) {
            result.add(v);
        }
        return result;
    }

    private void ensureCollection() {
        try {
            restTemplate.getForObject(qdrantUrl + "/collections/" + collectionName, String.class);
        } catch (Exception e) {
            log.info("[知识库] 创建Qdrant集合，向量维度: [16]");
            post("/collections/" + collectionName, Map.of(
                    "vectors", Map.of("size", 16, "distance", "Cosine")
            ));
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(qdrantUrl + path, entity, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.warn("[知识库] Qdrant请求失败: {}", e.getMessage());
            try {
                return objectMapper.createObjectNode();
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
