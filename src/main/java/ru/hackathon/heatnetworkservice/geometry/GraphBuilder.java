package ru.hackathon.heatnetworkservice.geometry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Component
@RequiredArgsConstructor
public class GraphBuilder {

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final ObstacleChecker obstacleChecker;
    private final CoordinateTransformer coordinateTransformer;

    // Максимальное расстояние для ребра (м) — чтобы не строить миллионы рёбер
    private static final double MAX_EDGE_LENGTH = 2000.0;

    // Радиус поиска вершины препятствия от целевого узла (м)
    private static final double TARGET_OBSTACLE_RADIUS = 500.0;

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
     * Храним координаты и в WGS84 (для вывода), и в UTM37N (для расчётов).
     */
    public static class Node {
        public String id;
        public Coordinate coordinateWgs84;
        public Coordinate coordinateUtm;
        public boolean isTarget;

        public Node(String id, Coordinate coordinateWgs84, Coordinate coordinateUtm, boolean isTarget) {
            this.id = id;
            this.coordinateWgs84 = coordinateWgs84;
            this.coordinateUtm = coordinateUtm;
            this.isTarget = isTarget;
        }
    }

    /**
     * Предвычисленное препятствие: UTM-геометрия + Envelope + тип.
     * Позволяет не преобразовывать координаты на каждой проверке.
     */
    private static class PreparedObstacle {
        final String restrictionType;
        final Geometry geometryUtm;
        final Envelope envelope;
        final boolean forbidden;

        PreparedObstacle(String restrictionType, Geometry geometryUtm, boolean forbidden) {
            this.restrictionType = restrictionType;
            this.geometryUtm = geometryUtm;
            this.envelope = geometryUtm.getEnvelopeInternal();
            this.forbidden = forbidden;
        }
    }

    /**
     * Строит граф с учётом вершин препятствий (visibility graph).
     * Использует предвычисленные препятствия и многопоточность.
     */
    public List<Edge> buildGraph(List<GeoObject> nodes, List<GeoObject> obstacles, int diameter) {
        log.info("Строим граф: {} узлов, {} препятствий, ДУ={}", nodes.size(), obstacles.size(), diameter);
        long startTime = System.currentTimeMillis();

        // 1. Целевые узлы (ОКС + камеры)
        List<Node> targetNodes = new ArrayList<>();
        for (GeoObject obj : nodes) {
            if (obj.getGeometry() == null) continue;
            Coordinate wgs84 = obj.getGeometry().getCoordinate();
            Geometry utmGeom = coordinateTransformer.toUtm37n(obj.getGeometry());
            if (utmGeom == null) continue;
            Coordinate utm = utmGeom.getCoordinate();
            targetNodes.add(new Node(obj.getId(), wgs84, utm, true));
        }

        // 2. Предвычисленные препятствия: UTM-геометрия + Envelope + тип
        List<PreparedObstacle> preparedObstacles = new ArrayList<>();
        for (GeoObject obstacle : obstacles) {
            String type = obstacle.getRestrictionType();
            if (type == null || obstacle.getGeometry() == null) continue;

            Geometry utmGeom = coordinateTransformer.toUtm37n(obstacle.getGeometry());
            if (utmGeom == null) continue;

            boolean forbidden = obstacleChecker.isForbidden(type);
            preparedObstacles.add(new PreparedObstacle(type, utmGeom, forbidden));
        }
        log.info("Целевых узлов: {}, подготовлено препятствий: {}",
                targetNodes.size(), preparedObstacles.size());

        // 3. Вершины запрещённых препятствий (для visibility graph)
        List<Node> obstacleNodes = new ArrayList<>();
        int counter = 0;
        for (PreparedObstacle po : preparedObstacles) {
            if (!po.forbidden) continue;
            for (Coordinate coordUtm : po.geometryUtm.getCoordinates()) {
                // WGS84 fallback берём из UTM — для obstacle-узлов WGS84 не критичен
                obstacleNodes.add(new Node("obs_" + counter++, coordUtm, coordUtm, false));
            }
        }
        log.info("Вершин препятствий: {}", obstacleNodes.size());

        // 4. Рёбра между целевыми узлами — параллельно
        ConcurrentLinkedQueue<Edge> edgesTargetTarget = new ConcurrentLinkedQueue<>();
        List<int[]> targetPairs = new ArrayList<>();
        for (int i = 0; i < targetNodes.size(); i++) {
            for (int j = i + 1; j < targetNodes.size(); j++) {
                targetPairs.add(new int[]{i, j});
            }
        }
        targetPairs.parallelStream().forEach(pair -> {
            Node from = targetNodes.get(pair[0]);
            Node to = targetNodes.get(pair[1]);
            Edge edge = tryBuildEdge(from, to, preparedObstacles, diameter);
            if (edge != null) {
                edgesTargetTarget.add(edge);
            }
        });
        log.info("Рёбер между целевыми: {}", edgesTargetTarget.size());

        // 5. Рёбра целевой ↔ близкая вершина препятствия — параллельно по целевым
        ConcurrentLinkedQueue<Edge> edgesTargetObstacle = new ConcurrentLinkedQueue<>();
        targetNodes.parallelStream().forEach(target -> {
            for (Node obsNode : obstacleNodes) {
                double dist = target.coordinateUtm.distance(obsNode.coordinateUtm);
                if (dist > TARGET_OBSTACLE_RADIUS) continue;

                Edge edge = tryBuildEdge(target, obsNode, preparedObstacles, diameter);
                if (edge != null) {
                    edgesTargetObstacle.add(edge);
                }
            }
        });
        log.info("Рёбер целевой-препятствие: {}", edgesTargetObstacle.size());

        // 6. Объединяем результаты
        List<Edge> allEdges = new ArrayList<>(edgesTargetTarget.size() + edgesTargetObstacle.size());
        allEdges.addAll(edgesTargetTarget);
        allEdges.addAll(edgesTargetObstacle);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Итого рёбер: {}, время построения графа: {} мс", allEdges.size(), elapsed);
        return allEdges;
    }

    /**
     * Пробует построить ребро между двумя узлами.
     * Возвращает null, если ребро невалидно.
     */
    private Edge tryBuildEdge(Node from, Node to, List<PreparedObstacle> prepared, int diameter) {
        // Создаём линию в UTM37N
        LineString line = geometryFactory.createLineString(
                new Coordinate[]{from.coordinateUtm, to.coordinateUtm});
        line.setSRID(32637);

        if (!isValidEdge(line, prepared, diameter, from, to)) {
            return null;
        }

        // Длина в метрах — корректно, так как координаты в UTM
        double dist = from.coordinateUtm.distance(to.coordinateUtm);
        double kspec = calculateMaxKspec(line, prepared);
        String layingMethod = (kspec > 1.0) ? "special" : "base";
        double costPerMeter = getCostPerMeter(diameter);
        double cost = dist * costPerMeter * kspec;

        return new Edge(from.id, to.id, line, dist, cost, layingMethod, kspec, diameter);
    }

    /**
     * Проверяет, можно ли провести трубу по прямой.
     * Работает с предвычисленными препятствиями (UTM + Envelope).
     */
    private boolean isValidEdge(LineString line, List<PreparedObstacle> prepared, int diameter,
                                Node from, Node to) {
        // Bounding box в UTM-координатах
        double minX = Math.min(from.coordinateUtm.x, to.coordinateUtm.x) - 100;
        double maxX = Math.max(from.coordinateUtm.x, to.coordinateUtm.x) + 100;
        double minY = Math.min(from.coordinateUtm.y, to.coordinateUtm.y) - 100;
        double maxY = Math.max(from.coordinateUtm.y, to.coordinateUtm.y) + 100;

        for (PreparedObstacle po : prepared) {
            // Быстрая проверка: если bounding box препятствия далеко — пропускаем
            Envelope env = po.envelope;
            if (env.getMaxX() < minX || env.getMinX() > maxX ||
                    env.getMaxY() < minY || env.getMinY() > maxY) {
                continue;
            }

            boolean intersects = line.intersects(po.geometryUtm);

            if (po.forbidden) {
                if (intersects) {
                    if ("oks".equals(po.restrictionType) && isFinalSegmentForOks(from, to, po.geometryUtm)) {
                        continue;
                    }
                    return false;
                }
            } else {
                if (intersects) {
                    if (!obstacleChecker.isAngleOk(line, po.geometryUtm, po.restrictionType)) {
                        return false;
                    }
                } else {
                    if (!obstacleChecker.isDistanceOk(line, po.geometryUtm, po.restrictionType, diameter)) {
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
    private boolean isFinalSegmentForOks(Node from, Node to, Geometry oksPolygonUtm) {
        boolean fromInside = oksPolygonUtm.contains(
                geometryFactory.createPoint(from.coordinateUtm));
        boolean toInside = oksPolygonUtm.contains(
                geometryFactory.createPoint(to.coordinateUtm));

        // Один внутри, другой снаружи — это финальный участок к ОКС
        return fromInside != toInside;
    }

    /**
     * Вычисляет максимальный Kспец для линии (среди всех пересекаемых разрешённых ограничений).
     * По ТЗ при наложении спецпроходов применяется наибольший коэффициент.
     */
    private double calculateMaxKspec(LineString line, List<PreparedObstacle> prepared) {
        double maxKspec = 1.0;
        for (PreparedObstacle po : prepared) {
            if (po.forbidden) continue;
            if (line.intersects(po.geometryUtm)) {
                double kspec = obstacleChecker.getKspec(po.restrictionType);
                if (kspec > maxKspec) maxKspec = kspec;
            }
        }
        return maxKspec;
    }

    /**
     * Стоимость 1 метра для ДУ (Таблица 1).
     */
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