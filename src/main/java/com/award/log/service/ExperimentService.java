package com.award.log.service;

import java.util.Map;

/**
 * 在线实验开关：记录样本数与差异率，用于规则/模型对照。
 */
public interface ExperimentService {
    boolean start(String name);
    boolean stop();
    Map<String, Object> report();
    boolean isRunning();
}
