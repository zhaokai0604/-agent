package com.award.log.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 Agent 运维与 AI 模型路由相关 {@code @ConfigurationProperties}。
 * 自身不含 Bean 定义，仅作为属性绑定入口。
 */
@Configuration
@EnableConfigurationProperties({AgentOpsProperties.class, AiModelRoutingProperties.class})
public class AgentOpsConfiguration {
}
