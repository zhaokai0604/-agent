package com.award.log.decision;

/**
 * 决策引擎类型：规则、随机森林、大模型。
 * 用于结果标注与轨迹落库，不表示运行时三者必须同时可用。
 */
public enum EngineType {
    RULE,
    RANDOM_FOREST,
    LLM
}
