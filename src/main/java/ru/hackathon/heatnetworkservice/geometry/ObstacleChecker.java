package ru.hackathon.heatnetworkservice.geometry;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ObstacleChecker {

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

    private static final Map<String, Double> KSPEC = Map.of(
            "road", 1.60,
            "tram_tracks", 1.75,
            "gas_pipeline", 1.25,
            "power_cable", 1.15,
            "heat_network", 1.05
    );

    private static final Map<String, Boolean> FORBIDDEN = Map.of(
            "oks", true,
            "park", true,
            "social_area", true,
            "prohibited_site", true,
            "water", true,
            "railway", true
    );

    private static final Map<String, Double> MIN_ANGLE = Map.of(
            "road", 45.0,
            "tram_tracks", 45.0
    );

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

    public double getHalfWidth(int diameter) {
        return PIPE_WIDTH.getOrDefault(diameter, 1.0) / 2.0;
    }

    public boolean isDistanceOk(Geometry newNetwork, Geometry obstacle,
                                String restrictionType, int diameter) {
        if (newNetwork == null || obstacle == null) return true;

        double minDistance = getMinDistance(restrictionType, diameter);
        double halfWidth = getHalfWidth(diameter);
        double actualDistance = newNetwork.distance(obstacle) - halfWidth;
        return actualDistance >= minDistance;
    }

    public boolean intersects(Geometry newNetwork, Geometry obstacle) {
        if (newNetwork == null || obstacle == null) return false;
        return newNetwork.intersects(obstacle);
    }

    public boolean canCross(String restrictionType) {
        return !isForbidden(restrictionType);
    }

    /**
     * Проверка угла пересечения В ТОЧКЕ ПЕРЕСЕЧЕНИЯ.
     * Для линейной геометрии — угол между направлениями линий.
     * Для полигональной — угол между направлением новой сети и границей
     * полигона в точке входа.
     */
    public boolean isAngleOk(LineString newNetwork, Geometry obstacle, String restrictionType) {
        Double minAngle = MIN_ANGLE.get(restrictionType);
        if (minAngle == null) return true;

        try {
            Geometry intersection = newNetwork.intersection(obstacle);
            if (intersection == null || intersection.isEmpty()) return true;

            Coordinate crossPoint = intersection.getCoordinate();
            if (crossPoint == null) return true;

            Coordinate tangentNew = tangentAt(newNetwork, crossPoint);
            if (tangentNew == null) return true;

            Coordinate tangentObs = null;
            if (obstacle instanceof LineString) {
                tangentObs = tangentAt((LineString) obstacle, crossPoint);
            } else if (obstacle instanceof Polygon) {
                Geometry boundary = obstacle.getBoundary();
                if (boundary instanceof LineString) {
                    tangentObs = tangentAt((LineString) boundary, crossPoint);
                }
            }
            if (tangentObs == null) return true;

            double angle = angleBetween(tangentNew, tangentObs);
            return angle >= minAngle;

        } catch (Exception e) {
            log.warn("Ошибка проверки угла: {}", e.getMessage());
            return true;
        }
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
        if (Math.abs(cross) > 1e-6) return false;
        double dot = (p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y);
        if (dot < 0) return false;
        double lenSq = (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y);
        return dot <= lenSq;
    }

    private double angleBetween(Coordinate v1, Coordinate v2) {
        double dot = v1.x * v2.x + v1.y * v2.y;
        double len1 = Math.hypot(v1.x, v1.y);
        double len2 = Math.hypot(v2.x, v2.y);
        if (len1 == 0 || len2 == 0) return 90.0;
        double cos = dot / (len1 * len2);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        double angle = Math.toDegrees(Math.acos(cos));
        if (angle > 90) angle = 180 - angle;
        return angle;
    }
}