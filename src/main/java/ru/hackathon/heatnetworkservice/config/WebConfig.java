package ru.hackathon.heatnetworkservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/dashboard.html");
        registry.addRedirectViewController("/swagger-ui.html", "/docs-ui/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/docs-ui/**")
                .addResourceLocations("classpath:/static/docs-ui/");

        registry.addResourceHandler("/dashboard.html")
                .addResourceLocations("classpath:/static/");
        registry.addResourceHandler("/swagger-custom.css")
                .addResourceLocations("classpath:/static/");
    }
}