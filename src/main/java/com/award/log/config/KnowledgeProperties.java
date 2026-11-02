package com.award.log.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库 RAG 配置（knowledge.*）：分块、检索 Top-K、向量维度与启动种子。
 * 字段默认值可被 application 配置覆盖；不包含实际向量检索逻辑。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {

    /** 是否启用知识库 RAG */
    private boolean enabled = true;

    /** 单块最大字符数 */
    private int chunkSize = 900;

    /** 块间重叠字符数 */
    private int chunkOverlap = 150;

    /** 对话检索 Top-K */
    private int searchTopK = 5;

    /** 向量维度（须与 embedding 模型一致） */
    private int vectorDimensions = 1024;

    /** 无 Embedding API 时是否降级为本地哈希向量 */
    private boolean allowLocalFallbackEmbedding = true;

    /** 启动时若知识库为空则写入内置 Runbook */
    private boolean seedOnStartup = true;
}
