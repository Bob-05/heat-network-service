package ru.hackathon.heatnetworkservice.geometry;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ObstacleChecker {

    /** ТЗ Табл.2: минимальное горизонтальное расстояние (м). */
    private static final Map<String, Double> MIN_DISTANCE = new HashMap<>();
    static {
        MIN_DISTANCE.put("park",             1.0);
        MIN_DISTANCE.put("social_area",      1.0);
        MIN_DISTANCE.put("prohibited_site",  1.0);
        MIN_DISTANCE.put("water",            1.0);
        MIN_DISTANCE.put("railway",          1.0);
        MIN_DISTANCE.put("road",             1.5);
        MIN_DISTANCE.put("tram_tracks",      1.5);
        MIN_DISTANCE.put("gas_pipeline",     2.0);
        MIN_DISTANCE.put("power_cable",      2.0);
        MIN_DISTANCE.put("heat_network",     1.0);
    }

    /** ТЗ Табл.2: K_спец для специальных проходов. */
    private static final Map<String, Double> KSPEC = new HashMap<>();
    static {
        KSPEC.put("road",         1.60);
        KSPEC.put("tram_tracks",  1.75);
        KSPEC.put("gas_pipeline", 1.25);
        KSPEC.put("power_cable",  1.15);
        KSPEC.put("heat_network", 1.05);
    }

    /** ТЗ Табл.2: запрет пересечения. */
    private static final Map<String, Boolean> FORBIDDEN = new HashMap<>();
    static {
        FORBIDDEN.put("oks",             true);
        FORBIDDEN.put("park",            true);
        FORBIDDEN.put("social_area",     true);
        FORBIDDEN.put("prohibited_site", true);
        FORBIDDEN.put("water",           true);
        FORBIDDEN.put("railway",         true);
    }

    /** ТЗ Табл.2: минимальный угол пересечения (град.). */
    private static final Map<String, Double> MIN_ANGLE = new HashMap<>();
    static {
        MIN_ANGLE.put("road",        45.0);
        MIN_ANGLE.put("tram_tracks", 45.0);
    }

    /**
     * ТЗ п.8.2: собственный расчётный габарит ограничения (полуширина).
     * Для газопровода 0.40×0.40, для силового кабеля 0.20×0.20.
     */
    private static final Map<String, Double> OBSTACLE_HALF_WIDTH = new HashMap<>();
    static {
        OBSTACLE_HALF_WIDTH.put("gas_pipeline", 0.20); // 0.40 / 2
        OBSTACLE_HALF_WIDTH.put("power_cable",  0.10); // 0.20 / 2
    }

    /** ТЗ Табл.1: расчётная ширина пары труб по ДУ (м). */
    private static final Map<Integer, Double> PIPE_WIDTH = new HashMap<>();
    static {
        PIPE_WIDTH.put(50,   0.400);
        PIPE_WIDTH.put(65,   0.430);
        PIPE_WIDTH.put(80,   0.470);
        PIPE_WIDTH.put(100,  0.510);
        PIPE_WIDTH.put(125,  0.600);
        PIPE_WIDTH.put(150,  0.650);
        PIPE_WIDTH.put(200,  0.880);
        PIPE_WIDTH.put(250,  1.050);
        PIPE_WIDTH.put(300,  1.150);
        PIPE_WIDTH.put(400,  1.370);
        PIPE_WIDTH.put(500,  1.670);
        PIPE_WIDTH.put(600,  1.850);
        PIPE_WIDTH.put(700,  2.050);
        PIPE_WIDTH.put(800,  2.250);
        PIPE_WIDTH.put(900,  2.450);
        PIPE_WIDTH.put(1000, 2.650);
        PIPE_WIDTH.put(1200, 3.100);
        PIPE_WIDTH.put(1400, 3.450);
    }

    /** ТЗ Табл.2 + разъяснение 3: отступ до полигона ОКС зависит от ДУ. */
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

    public boolean isSpecial(String restrictionType) {
        return KSPEC.containsKey(restrictionType);
    }

    public double getHalfWidth(int diameter) {
        return PIPE_WIDTH.getOrDefault(diameter, 1.0) / 2.0;
    }

    public double getObstacleHalfWidth(String restrictionType) {
        return OBSTACLE_HALF_WIDTH.getOrDefault(restrictionType, 0.0);
    }

    /**
     * ТЗ п.3.1, п.8.2:
     *  - для полигонального ограничения: расстояние от границы полигона
     *    до внешней границы расчётного габарита новой сети;
     *  - для линейного: от геометрии ограничения до внешней границы;
     *  - если у ограничения задан собственный габарит (газопровод, кабель):
     *    между внешними границами двух габаритов.
     */
    public boolean isDistanceOk(Geometry newNetwork, Geometry obstacle,
                                String restrictionType, int diameter) {
        if (newNetwork == null || obstacle == null) return true;

        double minDistance       = getMinDistance(restrictionType, diameter);
        double halfWidth         = getHalfWidth(diameter);
        double obstacleHalfWidth = getObstacleHalfWidth(restrictionType);

        double actualDistance = newNetwork.distance(obstacle) - halfWidth - obstacleHalfWidth;
        return actualDistance >= minDistance - 1e-6;
    }

    public boolean intersects(Geometry newNetwork, Geometry obstacle) {
        if (newNetwork == null || obstacle == null) return false;
        return newNetwork.intersects(obstacle);
    }

    public boolean canCross(String restrictionType) {
        return !isForbidden(restrictionType);
    }

    /**
     * ТЗ п.4, п.8.1: проверка угла пересечения.
     *  - для линейной геометрии — угол между направлением новой сети
     *    и направлением линии ограничения в точке пересечения;
     *  - для полигональной — угол между направлением новой сети и
     *    границей полигона в ТОЧКЕ ВХОДА специального участка
     *    (первая точка пересечения новой сети с границей полигона
     *    вдоль направления от начала к концу ребра).
     */
    public boolean isAngleOk(LineString newNetwork, Geometry obstacle, String restrictionType) {
        Double minAngle = MIN_ANGLE.get(restrictionType);
        if (minAngle == null) return true;

        try {
            Coordinate entryPoint = findEntryPoint(newNetwork, obstacle);
            if (entryPoint == null) return true;

            Coordinate tangentNew = tangentAt(newNetwork, entryPoint);
            if (tangentNew == null) return true;

            Coordinate tangentObs = null;
            if (obstacle instanceof LineString) {
                tangentObs = tangentAt((LineString) obstacle, entryPoint);
            } else if (obstacle instanceof Polygon) {
                Geometry boundary = obstacle.getBoundary();
                if (boundary instanceof LineString) {
                    tangentObs = tangentAt((LineString) boundary, entryPoint);
                } else if (boundary != null) {
                    // MultiLineString: ищем ту линию, на которой лежит точка
                    for (int i = 0; i < boundary.getNumGeometries(); i++) {
                        Geometry g = boundary.getGeometryN(i);
                        if (g instanceof LineString) {
                            Coordinate t = tangentAt((LineString) g, entryPoint);
                            if (t != null) { tangentObs = t; break; }
                        }
                    }
                }
            }
            if (tangentObs == null) return true;

            double angle = angleBetween(tangentNew, tangentObs);
            return angle >= minAngle - 1e-6;

        } catch (Exception e) {
            log.warn("Ошибка проверки угла для {}: {}", restrictionType, e.getMessage());
            return true;
        }
    }

    /**
     * Находит первую (вдоль направления newNetwork) точку пересечения
     * с границей препятствия. Для полигона — это точка ВХОДА.
     */
    private Coordinate findEntryPoint(LineString newNetwork, Geometry obstacle) {
        Geometry ref = obstacle;
        if (obstacle instanceof Polygon || obstacle instanceof org.locationtech.jts.geom.MultiPolygon) {
            ref = obstacle.getBoundary();
        }
        Geometry inter = newNetwork.intersection(ref);
        if (inter == null || inter.isEmpty()) return null;

        Coordinate start = newNetwork.getCoordinateN(0);
        Coordinate best = null;
        double bestDist = Double.POSITIVE_INFINITY;

        // Перебираем все точки пересечения и берём ближайшую к началу ребра.
        for (int i = 0; i < inter.getNumGeometries(); i++) {
            Geometry g = inter.getGeometryN(i);
            for (Coordinate c : g.getCoordinates()) {
                double d = c.distance(start);
                if (d < bestDist) { bestDist = d; best = c; }
            }
        }
        return best;
    }

    private Coordinate tangentAt(LineString line, Coordinate point) {
        Coordinate[] coords = line.getCoordinates();
        for (int i = 0; i < coords.length - 1; i++) {
            if (isPointOnSegment(point, coords[i], coords[i + 1])) {
                return new Coordinate(
                        coords[i + 1].x - coords[i].x,
                        coords[i + 1].y - coords[i].y);
            }
        }
        return null;
    }

    private boolean isPointOnSegment(Coordinate p, Coordinate a, Coordinate b) {
        double cross = (p.y - a.y) * (b.x - a.x) - (p.x - a.x) * (b.y - a.y);
        double dx = b.x - a.x, dy = b.y - a.y;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-12) return false;
        if (Math.abs(cross) / Math.sqrt(lenSq) > 1e-3) return false;
        double dot = (p.x - a.x) * dx + (p.y - a.y) * dy;
        return dot >= -1e-6 && dot <= lenSq + 1e-6;
    }

    /** Возвращает угол в [0°, 90°] между двумя направляющими векторами. */
    private double angleBetween(Coordinate v1, Coordinate v2) {
        double dot = v1.x * v2.x + v1.y * v2.y;
        double len1 = Math.hypot(v1.x, v1.y);
        double len2 = Math.hypot(v2.x, v2.y);
        if (len1 < 1e-12 || len2 < 1e-12) return 90.0;
        double cos = Math.max(-1.0, Math.min(1.0, dot / (len1 * len2)));
        double angle = Math.toDegrees(Math.acos(cos));
        if (angle > 90) angle = 180 - angle;
        return angle;
    }
}