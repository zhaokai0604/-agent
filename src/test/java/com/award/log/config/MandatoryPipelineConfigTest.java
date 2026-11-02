package com.award.log.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;

class MandatoryPipelineConfigTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(OpsPepProperties.class)
    static class Config {}
    @Test void disablingThePipelineFailsStartupWithAMigrationHint() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues("ops.pep.enabled=false")
                .run(context->{
                    assertNotNull(context.getStartupFailure());
                    Throwable error=context.getStartupFailure();while(error.getCause()!=null)error=error.getCause();
                    assertTrue(error.getMessage().contains("ops.pep.enabled=false"));
                });
    }
    @Test void defaultsAndExplicitEnablementRemainValid() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBean(OpsPepProperties.class).isEnabled());});
    }
}
