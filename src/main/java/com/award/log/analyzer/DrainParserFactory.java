package com.award.log.analyzer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Component
public class DrainParserFactory {

    private final DrainParser classicParser;
    private final DrainParser plusParser;
    private final DrainParser activeParser;

    public DrainParserFactory(
            @Qualifier("drainParserV2") DrainParser classicParser,
            @Qualifier("drainPlusParser") DrainParser plusParser,
            @org.springframework.beans.factory.annotation.Value("${log.analyzer.drain.engine:plus}") String engineType) {
        this.classicParser = classicParser;
        this.plusParser = plusParser;

        if ("classic".equalsIgnoreCase(engineType)) {
            this.activeParser = classicParser;
            log.info("[DrainParserFactory] 使用经典Drain解析器 (v2)");
        } else {
            this.activeParser = plusParser;
            log.info("[DrainParserFactory] 使用 Drain-Plus（长度分桶 + 前缀 Trie + 簇内相似度合并）");
        }
    }

    public DrainParser getParser() {
        return activeParser;
    }

    public DrainParser getParser(String engineType) {
        if ("classic".equalsIgnoreCase(engineType)) {
            return classicParser;
        } else if ("plus".equalsIgnoreCase(engineType)) {
            return plusParser;
        }
        return activeParser;
    }

    public DrainParser getClassicParser() {
        return classicParser;
    }

    public DrainParser getPlusParser() {
        return plusParser;
    }

    public void switchEngine(String engineType) {
        log.info("[DrainParserFactory] 切换解析引擎: {}", engineType);
    }

    public Map<String, Object> getAllStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("classic", classicParser.getStats());
        stats.put("plus", plusParser.getStats());
        stats.put("active", activeParser.getVersion());
        return stats;
    }
}
