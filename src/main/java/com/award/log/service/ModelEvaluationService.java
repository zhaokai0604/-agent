package com.award.log.service;

import java.util.List;
import java.util.Map;

/**
 * 模型评估：对标注集打分并落库 precision/recall 等指标。
 */
public interface ModelEvaluationService {
    Map<String, Object> evaluate(String modelVersion, List<Map<String, Object>> dataset);
}
