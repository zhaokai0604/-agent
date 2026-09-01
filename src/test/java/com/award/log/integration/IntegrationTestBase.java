package com.award.log.integration;

import com.award.log.service.ElasticsearchService;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        // 防止本机 config/application-local.yml 覆盖测试 H2（否则 mapper-h2 的 DATEADD 会打到 MySQL）
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:log_analysis_${random.int};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=test",
        "mybatis.mapper-locations=classpath:mapper-h2/*.xml",
        // Never inherit developer credentials or call external vector stores in tests.
        "knowledge.enabled=false",
        "knowledge.seed-on-startup=false",
        "qdrant.url=http://127.0.0.1:9",
        "qdrant.api-key=",
        "agent.awm.seed-on-startup=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    @MockBean
    ElasticsearchService elasticsearchService;
}
