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

    private static final double MAX_EDGE_LENGTH = 2000.0;
    private static final double TARGET_OBSTACLE_RADIUS = 500.0;
    private static final double NETWORK_SPLIT_STEP_M = 2.0;
    private static final double NETWORK_POINT_RADIUS_M = 300.0;

    public static class Edge {
        public String fromId;
        public String toId;
        public Coordinate fromCoordinateUtm;   // НОВОЕ
        public Coordinate toCoordinateUtm;     // НОВОЕ
        public LineString geometry;
        public double length;
        public double cost;
        public String layingMethod;
        public double kspec;
        public int diameter;

        public Edge(String fromId, String toId,
                    Coordinate fromCoordinateUtm, Coordinate toCoordinateUtm,
                    LineString geometry, double length, double cost,
                    String layingMethod, double kspec, int diameter) {
            this.fromId = fromId;
            this.toId = toId;
            this.fromCoordinateUtm = fromCoordinateUtm;
            this.toCoordinateUtm = toCoordinateUtm;
            this.geometry = geometry;
            this.length = length;
            this.cost = cost;
            this.layingMethod = layingMethod;
            this.kspec = kspec;
            this.diameter = diameter;
        }
    }

    public static class Node {
        public String id;
        public Coordinate coordinateWgs84;
        public Coordinate coordinateUtm;
        public boolean isTarget;
        public boolean isNetworkPoint;

        public Node(String id, Coordinate coordinateWgs84, Coordinate coordinateUtm,
                    boolean isTarget, boolean isNetworkPoint) {
            this.id = id;
            this.coordinateWgs84 = coordinateWgs84;
            this.coordinateUtm = coordinateUtm;
            this.isTarget = isTarget;
            this.isNetworkPoint = isNetworkPoint;
        }
    }

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

    public List<Edge> buildGraph(
            List<GeoObject> nodes,
            List<GeoObject> oksPoints,
            List<GeoObject> obstacles,
            List<GeoObject> existingNetworks,
            int diameter
    ) {
        log.info("Строим граф: {} узлов, {} ОКС, {} препятствий, {} сетей, ДУ={}",
                nodes.size(), oksPoints.size(), obstacles.size(),
                existingNetworks.size(), diameter);
        long startTime = System.currentTimeMillis();

        // 1. Целевые узлы (ОКС + камеры)
        List<Node> targetNodes = new ArrayList<>();
        for (GeoObject obj : nodes) {
            if (obj.getGeometry() == null) continue;
            Coordinate wgs84 = obj.getGeometry().getCoordinate();
            Geometry utmGeom = coordinateTransformer.toUtm37n(obj.getGeometry());
            if (utmGeom == null) continue;
            Coordinate utm = utmGeom.getCoordinate();
            targetNodes.add(new Node(obj.getId(), wgs84, utm, true, false));
        }

        // 2. Точки существующих сетей — только в радиусе от ОКС
        List<Coordinate> oksCoordsUtm = new ArrayList<>();
        for (GeoObject oks : oksPoints) {
            if (oks.getGeometry() == null) continue;
            Geometry utmGeom = coordinateTransformer.toUtm37n(oks.getGeometry());
            if (utmGeom != null) oksCoordsUtm.add(utmGeom.getCoordinate());
        }

        List<Node> networkNodes = new ArrayList<>();
        int networkPointCounter = 0;
        for (GeoObject net : existingNetworks) {
            if (net.getGeometry() == null) continue;
            Geometry utmGeom = coordinateTransformer.toUtm37n(net.getGeometry());
            if (utmGeom == null) continue;

            Coordinate[] coords = utmGeom.getCoordinates();
            for (int i = 0; i < coords.length - 1; i++) {
                Coordinate a = coords[i];
                Coordinate b = coords[i + 1];
                double segLen = a.distance(b);
                int steps = (int) Math.ceil(segLen / NETWORK_SPLIT_STEP_M);

                for (int s = 0; s <= steps; s++) {
                    double t = (double) s / steps;
                    double x = a.x + t * (b.x - a.x);
                    double y = a.y + t * (b.y - a.y);
                    Coordinate point = new Coordinate(x, y);

                    boolean nearOks = false;
                    for (Coordinate oksCoord : oksCoordsUtm) {
                        if (point.distance(oksCoord) <= NETWORK_POINT_RADIUS_M) {
                            nearOks = true;
                            break;
                        }
                    }
                    if (!nearOks) continue;

                    String netPointId = "net_" + net.getId() + "_" + (networkPointCounter++);
                    networkNodes.add(new Node(netPointId, point, point, true, true));
                }
            }
        }

        log.info("Целевых узлов: {} (ОКС+камеры), точек сетей: {}",
                targetNodes.size(), networkNodes.size());

        // 3. Предвычисленные препятствия
        List<PreparedObstacle> preparedObstacles = new ArrayList<>();
        for (GeoObject obstacle : obstacles) {
            String type = obstacle.getRestrictionType();
            if (type == null || obstacle.getGeometry() == null) continue;
            Geometry utmGeom = coordinateTransformer.toUtm37n(obstacle.getGeometry());
            if (utmGeom == null) continue;
            boolean forbidden = obstacleChecker.isForbidden(type);
            preparedObstacles.add(new PreparedObstacle(type, utmGeom, forbidden));
        }
        log.info("Подготовлено препятствий: {}", preparedObstacles.size());

        // 4. Вершины запрещённых препятствий
        List<Node> obstacleNodes = new ArrayList<>();
        int counter = 0;
        for (PreparedObstacle po : preparedObstacles) {
            if (!po.forbidden) continue;
            for (Coordinate coordUtm : po.geometryUtm.getCoordinates()) {
                obstacleNodes.add(new Node("obs_" + counter++, coordUtm, coordUtm, false, false));
            }
        }
        log.info("Вершин препятствий: {}", obstacleNodes.size());

        // Собираем все целевые узлы
        List<Node> allTargetNodes = new ArrayList<>();
        allTargetNodes.addAll(targetNodes);
        allTargetNodes.addAll(networkNodes);

        // 5. Рёбра целевой ↔ точка сети / целевой ↔ целевой
        ConcurrentLinkedQueue<Edge> edgesTargetNetwork = new ConcurrentLinkedQueue<>();
        allTargetNodes.parallelStream().forEach(from -> {
            for (Node to : allTargetNodes) {
                if (from == to) continue;
                if (from.isNetworkPoint && to.isNetworkPoint) continue;
                if (!from.isNetworkPoint && !to.isNetworkPoint) {
                    if (from.id.compareTo(to.id) >= 0) continue;
                }
                double dist = from.coordinateUtm.distance(to.coordinateUtm);
                if (dist > MAX_EDGE_LENGTH) continue;

                Edge edge = tryBuildEdge(from, to, preparedObstacles, diameter);
                if (edge != null) {
                    edgesTargetNetwork.add(edge);
                }
            }
        });
        log.info("Рёбер целевой-сеть: {}", edgesTargetNetwork.size());

        // 6. Рёбра целевой ↔ вершина препятствия (радиус 500 м)
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

        List<Edge> allEdges = new ArrayList<>();
        allEdges.addAll(edgesTargetNetwork);
        allEdges.addAll(edgesTargetObstacle);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Итого рёбер: {}, время построения графа: {} мс", allEdges.size(), elapsed);
        return allEdges;
    }

    private Edge tryBuildEdge(Node from, Node to, List<PreparedObstacle> prepared, int diameter) {
        LineString line = geometryFactory.createLineString(
                new Coordinate[]{from.coordinateUtm, to.coordinateUtm});
        line.setSRID(32637);

        if (!isValidEdge(line, prepared, diameter, from, to)) {
            return null;
        }

        double dist = from.coordinateUtm.distance(to.coordinateUtm);
        double kspec = calculateMaxKspec(line, prepared);
        String layingMethod = (kspec > 1.0) ? "special" : "base";
        double costPerMeter = getCostPerMeter(diameter);
        double cost = dist * costPerMeter * kspec;

        return new Edge(from.id, to.id,
                from.coordinateUtm, to.coordinateUtm,   // НОВОЕ
                line, dist, cost, layingMethod, kspec, diameter);
    }

    private boolean isValidEdge(LineString line, List<PreparedObstacle> prepared, int diameter,
                                Node from, Node to) {
        double minX = Math.min(from.coordinateUtm.x, to.coordinateUtm.x) - 100;
        double maxX = Math.max(from.coordinateUtm.x, to.coordinateUtm.x) + 100;
        double minY = Math.min(from.coordinateUtm.y, to.coordinateUtm.y) - 100;
        double maxY = Math.max(from.coordinateUtm.y, to.coordinateUtm.y) + 100;

        for (PreparedObstacle po : prepared) {
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

    private boolean isFinalSegmentForOks(Node from, Node to, Geometry oksPolygonUtm) {
        boolean fromInside = oksPolygonUtm.contains(
                geometryFactory.createPoint(from.coordinateUtm));
        boolean toInside = oksPolygonUtm.contains(
                geometryFactory.createPoint(to.coordinateUtm));
        return fromInside != toInside;
    }

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