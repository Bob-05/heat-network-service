package ru.hackathon.heatnetworkservice.geometry;

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

    /** Минимальная длина ребра графа (м). */
    private double maxEdgeLengthMin = 2000.0;

    /** Шаг разбиения существующих сетей на точки (м). */
    private double networkSplitStep = 5.0;

    /** Стартовый радиус поиска точек сети от ОКС (м). */
    private double networkRadiusStart = 100.0;

    /** Множитель расширения радиуса на каждой итерации. */
    private double networkRadiusMultiplier = 2.0;

    /** Максимальное количество итераций расширения радиуса. */
    private int maxRadiusIterations = 10;

    /** Количество итераций без прогресса до остановки. */
    private int noProgressIterationsToStop = 2;

    /** Радиус поиска вершины препятствия от целевого узла (м). */
    private double targetObstacleRadius = 500.0;
}