package ru.hackathon.heatnetworkservice.geometry;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ObstacleChecker {

    /**
     * Минимальные горизонтальные расстояния (м).
     */
    private static final Map<String, Double> MIN_DISTANCE = Map.of(
            "park", 1.0,
            "social_area", 1.0,
            "prohibited_site", 1.0,
            "water", 1.0,
            "railway", 1.0,
            "road", 1.5,
            "tram_tracks", 1.5,
            "gas_pipeline", 2.0,
            "power_cable", 2.0,
            "heat_network", 1.0
    );

    /**
     * Коэффициенты специального прохода.
     */
    private static final Map<String, Double> KSPEC = Map.of(
            "road", 1.60,
            "tram_tracks", 1.75,
            "gas_pipeline", 1.25,
            "power_cable", 1.15,
            "heat_network", 1.05
    );

    /**
     * Типы, которые НЕЛЬЗЯ пересекать.
     */
    private static final Map<String, Boolean> FORBIDDEN = Map.of(
            "oks", true,
            "park", true,
            "social_area", true,
            "prohibited_site", true,
            "water", true,
            "railway", true
    );

    /**
     * Минимальный угол пересечения (в градусах).
     */
    private static final Map<String, Double> MIN_ANGLE = Map.of(
            "road", 45.0,
            "tram_tracks", 45.0
    );

    /**
     * Расчётная ширина пары труб (м) по Таблице 1.
     */
    private static final Map<Integer, Double> PIPE_WIDTH = Map.ofEntries(
            Map.entry(50, 0.400),
            Map.entry(65, 0.430),
            Map.entry(80, 0.470),
            Map.entry(100, 0.510),
            Map.entry(125, 0.600),
            Map.entry(150, 0.650),
            Map.entry(200, 0.880),
            Map.entry(250, 1.050),
            Map.entry(300, 1.150),
            Map.entry(400, 1.370),
            Map.entry(500, 1.670),
            Map.entry(600, 1.850),
            Map.entry(700, 2.050),
            Map.entry(800, 2.250),
            Map.entry(900, 2.450),
            Map.entry(1000, 2.650),
            Map.entry(1200, 3.100),
            Map.entry(1400, 3.450)
    );

    public double getMinDistance(String restrictionType, int diameter) {
        if ("oks".equals(restrictionType)) {
            if (diameter < 500) return 5.0;
            if (diameter <= 800) return 7.0;
            return 9.0;
        }
        return MIN_DISTANCE.getOrDefault(restrictionType, 0.0);
    }

    public double getKspec(String restrictionType) {
        return KSPEC.getOrDefault(restrictionType, 1.0);
    }

    public boolean isForbidden(String restrictionType) {
        return FORBIDDEN.getOrDefault(restrictionType, false);
    }

    /**
     * Возвращает половину расчётной ширины пары труб (м).
     */
    public double getHalfWidth(int diameter) {
        return PIPE_WIDTH.getOrDefault(diameter, 1.0) / 2.0;
    }

    /**
     * Проверяет, находится ли геометрия на допустимом расстоянии от препятствия
     * с учётом расчётного габарита новой сети.
     */
    public boolean isDistanceOk(Geometry newNetwork, Geometry obstacle, String restrictionType, int diameter) {
        if (newNetwork == null || obstacle == null) {
            return true;
        }

        double minDistance = getMinDistance(restrictionType, diameter);
        double halfWidth = getHalfWidth(diameter);
        // Расстояние от оси новой сети до препятствия минус полширины = расстояние от габарита до препятствия
        double actualDistance = newNetwork.distance(obstacle) - halfWidth;

        /*
        boolean ok = actualDistance >= minDistance;
        if (!ok) {
            log.debug("Расстояние {} < {} для типа {} (ДУ={})",
                    actualDistance, minDistance, restrictionType, diameter);
        }
        */

        return actualDistance >= minDistance;
    }

    public boolean intersects(Geometry newNetwork, Geometry obstacle) {
        if (newNetwork == null || obstacle == null) {
            return false;
        }
        return newNetwork.intersects(obstacle);
    }

    public boolean canCross(String restrictionType) {
        return !isForbidden(restrictionType);
    }

    /**
     * Проверяет угол пересечения линии новой сети с линейным ограничением.
     * Возвращает true, если угол >= минимального.
     */
    public boolean isAngleOk(LineString newNetwork, Geometry obstacle, String restrictionType) {
        Double minAngle = MIN_ANGLE.get(restrictionType);
        if (minAngle == null) {
            return true; // Для этого типа нет требования по углу
        }

        // Если препятствие — не линия, а полигон, проверяем угол с границей
        // Упрощённо: проверяем угол с первой линией пересечения
        try {
            Geometry intersection = newNetwork.intersection(obstacle);
            if (intersection == null || intersection.isEmpty()) {
                return true;
            }

            // Если препятствие — линия
            if (obstacle instanceof LineString) {
                return calculateAngle(newNetwork, (LineString) obstacle) >= minAngle;
            }

            // Если препятствие — полигон, берём его границу
            if (obstacle.getBoundary() instanceof LineString) {
                return calculateAngle(newNetwork, (LineString) obstacle.getBoundary()) >= minAngle;
            }

            // По умолчанию — пропускаем
            return true;

        } catch (Exception e) {
            log.warn("Ошибка проверки угла: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Вычисляет угол между двумя линиями (в градусах).
     */
    private double calculateAngle(LineString line1, LineString line2) {
        Coordinate[] coords1 = line1.getCoordinates();
        Coordinate[] coords2 = line2.getCoordinates();

        if (coords1.length < 2 || coords2.length < 2) {
            return 90.0; // По умолчанию
        }

        // Берём первую и последнюю точку каждой линии
        double dx1 = coords1[coords1.length - 1].x - coords1[0].x;
        double dy1 = coords1[coords1.length - 1].y - coords1[0].y;
        double dx2 = coords2[coords2.length - 1].x - coords2[0].x;
        double dy2 = coords2[coords2.length - 1].y - coords2[0].y;

        double angle1 = Math.atan2(dy1, dx1);
        double angle2 = Math.atan2(dy2, dx2);

        double diff = Math.abs(Math.toDegrees(angle1 - angle2));
        // Угол между линиями — от 0 до 90
        if (diff > 90) {
            diff = 180 - diff;
        }
        return diff;
    }
}