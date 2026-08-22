package com.award.log.analyzer;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Drain 日志模板解析契约：将原始日志映射为模板 ID/文本。
 * {@link #parallelParse} 按指定线程数并行聚类，供离线/批量分析使用；
 * 实现类需保证模板统计线程安全，本接口不定义持久化。
 */
public interface DrainParser {

    String parse(String raw);

    String parse(String raw, Consumer<TemplateInfo> onNewTemplate);

    /** 多线程解析一批原始日志，返回本批涉及的模板列表。 */
    List<TemplateInfo> parallelParse(List<String> rawLogs, int threads);

    int getTemplateCount();

    Map<String, Object> getStats();

    String getVersion();

    /** 单条 Drain 模板快照（ID、模板文本、命中计数），只读视图。 */
    interface TemplateInfo {
        String getTemplateId();
        String getTemplateText();
        long getCount();
    }
}
