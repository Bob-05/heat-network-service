package ru.hackathon.heatnetworkservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация параметров маршрутизации.
 * Значения можно менять в application.yml без перекомпиляции.
 *
 * Жёстко зафиксированные в ТЗ величины (ДУ, стоимость, габариты,
 * нормативные отступы, ограничение 10 м до существующей камеры,
 * ограничение ≤4 примыканий) остаются в своих сервисах.
 * Здесь — только служебные параметры построения графа.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "routing")
public class RoutingConfig {

    /** Максимальная длина ребра графа (м). */
    private double maxEdgeLength = 5000.0;

    /** Шаг разбиения существующих сетей на точки (м). */
    private double networkSplitStep = 5.0;

    /** Радиус поиска вершин препятствий от целевого узла (м). */
    private double targetObstacleRadius = 2000.0;

    /** Максимальное расстояние от существующей камеры до точки сети (м). */
    private double chamberToNetworkMax = 50.0;

    /** Смещение углового узла наружу от выпуклой оболочки препятствия (м). */
    private double cornerOffset = 10.0;

    /** Радиус поиска соседних узлов видимости (target + corner). */
    private double cornerCornerMax = 300.0;

    /** Радиус поиска сетевых точек вокруг углового узла (м). */
    private double cornerNetworkMax = 500.0;
}