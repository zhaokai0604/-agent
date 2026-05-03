package com.award.log.config;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("智能日志分析系统 API 接口文档")
                        .description("基于 AI 与 Drain 算法的异常日志分析系统后端服务接口文档")
                        .contact(new Contact().name("开发团队").url("https://github.com/award").email("dev@award.com"))
                        .version("1.0"));
    }
}