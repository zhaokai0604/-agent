package com.award.log.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * Elasticsearch配置类
 * 用于配置Elasticsearch的连接和初始化
 */
@Slf4j
@Configuration
@Profile("!dev") // 只在非开发环境启用Elasticsearch
@EnableElasticsearchRepositories(basePackages = "com.award.log.repository")
public class ElasticsearchConfig {

    // Elasticsearch的连接配置已在application.yml中定义
    // 这里主要用于启用Elasticsearch仓库

}
