package com.award.log.service;

import java.util.Map;

/**
 * 引擎健康检查：Drain、规则、随机森林、实验开关与模型可达性快照。
 */
public interface EngineHealthService {
    Map<String, Object> snapshot();
}
