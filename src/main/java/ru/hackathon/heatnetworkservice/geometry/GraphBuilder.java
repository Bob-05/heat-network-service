package ru.hackathon.heatnetworkservice.geometry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GraphBuilder {

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final ObstacleChecker obstacleChecker;

    // Максимальное расстояние для ребра (м) — чтобы не строить миллионы рёбер
    private static final double MAX_EDGE_LENGTH = 2000.0;

    public static class Edge {
        public String fromId;
        public String toId;
        public LineString geometry;
        public double length;
        public double cost;
        public String layingMethod;
        public double kspec;
        public int diameter;

        public Edge(String fromId, String toId, LineString geometry,
                    double length, double cost, String layingMethod, double kspec, int diameter) {
            this.fromId = fromId;
            this.toId = toId;
            this.geometry = geometry;
            this.length = length;
            this.cost = cost;
            this.layingMethod = layingMethod;
            this.kspec = kspec;
            this.diameter = diameter;
        }
    }

    /**
     * Узел графа — точка с ID.
     */
    public static class Node {
        public String id;
        public Coordinate coordinate;
        public boolean isTarget; // ОКС или камера

        public Node(String id, Coordinate coordinate, boolean isTarget) {
            this.id = id;
            this.coordinate = coordinate;
            this.isTarget = isTarget;
        }
    }

    /**
     * Строит граф с учётом вершин препятствий (visibility graph).
     */
    public List<Edge> buildGraph(List<GeoObject> nodes, List<GeoObject> obstacles, int diameter) {
        List<Edge> edges = new ArrayList<>();

        log.info("Строим граф: {} узлов, {} препятствий, ДУ={}", nodes.size(), obstacles.size(), diameter);

        // 1. Собираем все узлы: ОКС, камеры + вершины препятствий
        List<Node> allNodes = new ArrayList<>();

        // Целевые узлы (ОКС + камеры)
        for (GeoObject obj : nodes) {
            Coordinate coord = obj.getGeometry().getCoordinate();
            allNodes.add(new Node(obj.getId(), coord, true));
        }

        // Вершины препятствий (для обхода)
        int obstacleNodeCounter = 0;
        for (GeoObject obstacle : obstacles) {
            if (obstacle.getGeometry() == null) continue;
            String type = obstacle.getRestrictionType();
            // Только для запрещённых — нужны точки обхода
            if (type != null && obstacleChecker.isForbidden(type)) {
                Coordinate[] coords = obstacle.getGeometry().getCoordinates();
                for (Coordinate coord : coords) {
                    allNodes.add(new Node("obs_" + obstacleNodeCounter++, coord, false));
                }
            }
        }

        log.info("Всего узлов графа: {} (целевых: {}, вершин препятствий: {})",
                allNodes.size(), nodes.size(), allNodes.size() - nodes.size());

        // 2. Строим рёбра между парами узлов (только близкими)
        int totalPairs = 0;
        int validEdges = 0;

        for (int i = 0; i < allNodes.size(); i++) {
            for (int j = i + 1; j < allNodes.size(); j++) {
                Node from = allNodes.get(i);
                Node to = allNodes.get(j);

                // Пропускаем, если оба — не целевые (вершины препятствий)
                // Но НЕ пропускаем, если хотя бы один — целевой
                // (для связности через вершины)
                if (!from.isTarget && !to.isTarget) {
                    // Проверяем расстояние — не строим длинные рёбра между вершинами
                    double dist = from.coordinate.distance(to.coordinate);
                    if (dist > MAX_EDGE_LENGTH) continue;
                }

                // Проверяем расстояние между узлами
                double dist = from.coordinate.distance(to.coordinate);
                if (dist > MAX_EDGE_LENGTH) continue;

                totalPairs++;

                LineString line = geometryFactory.createLineString(
                        new Coordinate[]{from.coordinate, to.coordinate});
                line.setSRID(32637);

                if (isValidEdge(line, obstacles, diameter, from, to)) {
                    double kspec = calculateMaxKspec(line, obstacles);
                    String layingMethod = (kspec > 1.0) ? "special" : "base";
                    double costPerMeter = getCostPerMeter(diameter);
                    double cost = dist * costPerMeter * kspec;

                    edges.add(new Edge(from.id, to.id, line, dist, cost, layingMethod, kspec, diameter));
                    validEdges++;
                }
            }
        }

        log.info("Проверено пар: {}, валидных рёбер: {}", totalPairs, validEdges);
        return edges;
    }

    /**
     * Проверяет, можно ли провести трубу по прямой.
     */
    private boolean isValidEdge(LineString line, List<GeoObject> obstacles, int diameter,
                                Node from, Node to) {
        for (GeoObject obstacle : obstacles) {
            String restrictionType = obstacle.getRestrictionType();
            if (restrictionType == null) continue;

            Geometry obstacleGeom = obstacle.getGeometry();
            if (obstacleGeom == null) continue;

            boolean intersects = line.intersects(obstacleGeom);

            if (obstacleChecker.isForbidden(restrictionType)) {
                // Запрещено пересекать
                if (intersects) {
                    // Особый случай: если один из концов — целевой узел ВНУТРИ этого полигона
                    // и это финальный участок — разрешаем (по разъяснению 3)
                    if ("oks".equals(restrictionType) && isFinalSegmentForOks(from, to, obstacleGeom)) {
                        continue;
                    }
                    return false;
                }
            } else {
                // Разрешено пересекать (спецпроход)
                if (intersects) {
                    if (!obstacleChecker.isAngleOk(line, obstacleGeom, restrictionType)) {
                        return false;
                    }
                } else {
                    // Не пересекает — проверяем расстояние
                    if (!obstacleChecker.isDistanceOk(line, obstacleGeom, restrictionType, diameter)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Проверяет, является ли отрезок финальным для OKS.
     * Если один из концов внутри полигона OKS, а другой — снаружи,
     * и это линия к целевой точке — разрешаем (по разъяснению 3).
     */
    private boolean isFinalSegmentForOks(Node from, Node to, Geometry oksPolygon) {
        boolean fromInside = oksPolygon.contains(geometryFactory.createPoint(from.coordinate));
        boolean toInside = oksPolygon.contains(geometryFactory.createPoint(to.coordinate));

        // Один внутри, другой снаружи — это финальный участок к ОКС
        if (fromInside != toInside) {
            // Проверяем, что целевой узел — ОКС (не камера)
            // Для простоты: считаем, что если внутри полигона — это ОКС
            return true;
        }
        return false;
    }

    private double calculateMaxKspec(LineString line, List<GeoObject> obstacles) {
        double maxKspec = 1.0;
        for (GeoObject obstacle : obstacles) {
            String restrictionType = obstacle.getRestrictionType();
            if (restrictionType == null) continue;
            Geometry obstacleGeom = obstacle.getGeometry();
            if (obstacleGeom == null) continue;

            if (line.intersects(obstacleGeom)) {
                double kspec = obstacleChecker.getKspec(restrictionType);
                if (kspec > maxKspec) maxKspec = kspec;
            }
        }
        return maxKspec;
    }

    private double getCostPerMeter(int diameter) {
        switch (diameter) {
            case 50: return 74023;
            case 65: return 78631;
            case 80: return 83530;
            case 100: return 89748;
            case 125: return 97275;
            case 150: return 105507;
            case 200: return 120275;
            case 250: return 135323;
            case 300: return 150022;
            case 400: return 190299;
            case 500: return 224137;
            case 600: return 264790;
            case 700: return 324298;
            case 800: return 325996;
            case 900: return 327693;
            case 1000: return 418777;
            case 1200: return 428074;
            case 1400: return 683417;
            default: return 150022;
        }
    }
}