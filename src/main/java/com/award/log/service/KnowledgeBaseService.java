package com.award.log.service;

import java.util.List;
import java.util.Map;

public interface KnowledgeBaseService {
    Map<String, Object> upload(String title, String content);

    List<Map<String, Object>> search(String query, int topK);

    boolean delete(String id);
}
