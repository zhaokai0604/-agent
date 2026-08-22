package com.award.log.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时为 sys_user 补齐并发安全所需的数据库约束（幂等）。
 */
@Slf4j
@Component
public class SysUserSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public SysUserSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        ensureEmailUniqueIndex();
    }

    private void ensureEmailUniqueIndex() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND index_name = 'uk_email'",
                    Integer.class);
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE sys_user ADD UNIQUE KEY uk_email (email)");
            log.info("[sys_user] added unique index uk_email");
        } catch (Exception ex) {
            log.debug("[sys_user] uk_email ensure skipped or already present: {}", ex.getMessage());
        }
    }
}
