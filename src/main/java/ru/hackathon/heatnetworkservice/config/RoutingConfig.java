package ru.hackathon.heatnetworkservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация параметров маршрутизации.
 * Значения можно менять в application.yml без перекомпиляции.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "routing")
public class RoutingConfig {

    /** Максимальная длина ребра графа (м). */
    private double maxEdgeLength = 10000.0;

    /** Шаг разбиения существующих сетей на точки (м). */
    private double networkSplitStep = 5.0;

    /** Радиус поиска вершин препятствий от целевого узла (м). */
    private double targetObstacleRadius = 2000.0;
}