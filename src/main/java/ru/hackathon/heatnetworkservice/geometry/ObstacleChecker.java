package ru.hackathon.heatnetworkservice.geometry;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ObstacleChecker {

    private static final GeometryFactory GF = new GeometryFactory();

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

    /** ТЗ п.3.1: собственный расчётный габарит ограничения (полуширина). */
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
     * ТЗ п.3.1, разъяснение 7:
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
     * ТЗ п.4 + разъяснение 6.
     *   - LineString      — угол между направлением новой сети и линией;
     *   - MultiLineString — угол по конкретной линии, пересекающей трассу;
     *   - Polygon         — угол в точке входа относительно границы;
     *   - MultiPolygon    — то же, ищем нужную линию границы.
     *
     * Fail-closed: если угол не удалось определить для ограничения
     * с заданным минимумом — ребро отклоняется.
     */
    public boolean isAngleOk(LineString newNetwork, Geometry obstacle, String restrictionType) {
        Double minAngle = MIN_ANGLE.get(restrictionType);
        if (minAngle == null) return true;

        try {
            Coordinate entryPoint = findEntryPoint(newNetwork, obstacle);
            if (entryPoint == null) {
                log.warn("Не найдена точка входа при пересечении с {}", restrictionType);
                return false;
            }

            Coordinate tangentNew = tangentAt(newNetwork, entryPoint);
            if (tangentNew == null) {
                log.warn("Не определён тангенс новой сети в точке входа ({})", restrictionType);
                return false;
            }

            Coordinate tangentObs = tangentAtObstacle(obstacle, entryPoint);
            if (tangentObs == null) {
                log.warn("Не определён тангенс ограничения {} в точке входа", restrictionType);
                return false;
            }

            double angle = angleBetween(tangentNew, tangentObs);
            return angle >= minAngle - 1e-6;

        } catch (Exception e) {
            log.warn("Ошибка проверки угла для {}: {}", restrictionType, e.getMessage());
            return false;
        }
    }

    private Coordinate tangentAtObstacle(Geometry obstacle, Coordinate point) {
        Geometry ref = obstacle;

        if (obstacle instanceof Polygon || obstacle instanceof MultiPolygon) {
            ref = obstacle.getBoundary();
        }

        if (ref instanceof LineString) {
            return tangentAt((LineString) ref, point);
        }
        if (ref instanceof MultiLineString) {
            MultiLineString mls = (MultiLineString) ref;
            Point p = GF.createPoint(point);

            for (int i = 0; i < mls.getNumGeometries(); i++) {
                LineString g = (LineString) mls.getGeometryN(i);
                if (g.distance(p) < 1e-6) {
                    Coordinate t = tangentAt(g, point);
                    if (t != null) return t;
                }
            }
            double bestDist = Double.POSITIVE_INFINITY;
            Coordinate best = null;
            for (int i = 0; i < mls.getNumGeometries(); i++) {
                LineString g = (LineString) mls.getGeometryN(i);
                double d = g.distance(p);
                if (d < bestDist) {
                    Coordinate t = tangentAt(g, point);
                    if (t != null) {
                        bestDist = d;
                        best = t;
                    }
                }
            }
            return best;
        }
        return null;
    }

    private Coordinate findEntryPoint(LineString newNetwork, Geometry obstacle) {
        Geometry ref = obstacle;
        if (obstacle instanceof Polygon || obstacle instanceof MultiPolygon) {
            ref = obstacle.getBoundary();
        }
        Geometry inter = newNetwork.intersection(ref);
        if (inter == null || inter.isEmpty()) return null;

        Coordinate start = newNetwork.getCoordinateN(0);
        Coordinate best = null;
        double bestDist = Double.POSITIVE_INFINITY;

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