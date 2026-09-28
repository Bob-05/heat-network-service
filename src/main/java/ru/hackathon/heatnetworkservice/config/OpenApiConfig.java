package ru.hackathon.heatnetworkservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI heatNetworkOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Heat Network Service API")
                        .description("Сервис моделирования трасс подключения к тепловым сетям (ЛЦТ 2026)")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Heat Network Team")
                                .email("team@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local")
                ));
    }
}