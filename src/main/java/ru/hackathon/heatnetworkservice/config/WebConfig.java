package ru.hackathon.heatnetworkservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Конфигурация MVC.
 *
 * Всё что нужно:
 *   - редирект с корня "/" на дашборд.
 *
 * Ничего больше настраивать не требуется:
 *   - /dashboard.html отдаётся Spring Boot автоматически
 *     из classpath:/static/ (стандартный ResourceHandler).
 *   - /v3/api-docs генерируется springdoc-openapi-webmvc-core.
 *   - /swagger-custom.css лежит там же, где dashboard.html,
 *     и тоже отдаётся автоматически.
 *   - /docs-ui/** больше не используется: Swagger UI встроен
 *     прямо в dashboard.html как вкладка "API Docs".
 *   - /swagger-ui.html больше не используется по той же причине.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Корень → дашборд (в нём же вкладка с документацией).
        registry.addRedirectViewController("/", "/dashboard.html");
    }
}