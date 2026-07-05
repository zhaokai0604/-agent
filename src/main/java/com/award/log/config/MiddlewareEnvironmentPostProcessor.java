package com.award.log.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 在绑定完 application*.yml 后合并 {@code spring.autoconfigure.exclude}，
 * 使 Kafka / Redis 在未开启时不加载自动配置，避免启动期连接与错误日志。
 */
public class MiddlewareEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        SystemBootstrapSupport.reconcileForCurrentPlatform("startup-auto-reconcile");
        MapPropertySource systemConfigSource = loadSystemConfigSource(environment);
        if (systemConfigSource != null) {
            environment.getPropertySources().addFirst(systemConfigSource);
        }

        boolean kafkaOn = Boolean.parseBoolean(environment.getProperty("award.middleware.kafka", "false"));
        boolean redisOn = Boolean.parseBoolean(environment.getProperty("award.middleware.redis", "false"));
        boolean elasticsearchOn = Boolean.parseBoolean(environment.getProperty("spring.elasticsearch.enabled", "false"));

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        Binder binder = Binder.get(environment);
        List<String> existing = binder.bind(EXCLUDE_KEY, Bindable.listOf(String.class)).orElseGet(ArrayList::new);
        for (String s : existing) {
            if (StringUtils.hasText(s)) {
                merged.add(s.trim());
            }
        }
        if (merged.isEmpty()) {
            String csvExisting = environment.getProperty(EXCLUDE_KEY);
            if (StringUtils.hasText(csvExisting)) {
                for (String part : csvExisting.split(",")) {
                    if (StringUtils.hasText(part)) {
                        merged.add(part.trim());
                    }
                }
            }
        }

        if (!kafkaOn) {
            merged.add("org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        }
        if (!redisOn) {
            merged.add("org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        }
        if (!elasticsearchOn) {
            merged.add("org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.elasticsearch.ReactiveElasticsearchClientAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration");
            merged.add("org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration");
        }

        String csv = merged.stream().collect(Collectors.joining(","));
        java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put(EXCLUDE_KEY, csv);
        if (!redisOn) {
            properties.put("management.health.redis.enabled", "false");
        }
        if (!elasticsearchOn) {
            properties.put("management.health.elasticsearch.enabled", "false");
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                "award-middleware-autoconfigure-exclude",
                properties));
    }

    private MapPropertySource loadSystemConfigSource(ConfigurableEnvironment environment) {
        String appConfigSecret = environment.getProperty("APP_CONFIG_SECRET", "");
        java.util.Map<String, Object> props = SystemConfigFileSupport.toSpringProperties(
                SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.overrideFile()),
                SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.secretFile()),
                appConfigSecret);
        if (props.isEmpty()) {
            return null;
        }
        return new MapPropertySource("award-system-config-overrides", props);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 20;
    }
}
