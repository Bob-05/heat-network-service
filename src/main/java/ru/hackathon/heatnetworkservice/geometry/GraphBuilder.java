package ru.hackathon.heatnetworkservice.geometry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.stereotype.Component;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Component
@RequiredArgsConstructor
public class GraphBuilder {

    /** Максимальное расстояние от существующей камеры до точки сети. */
    private static final double CHAMBER_TO_NETWORK_MAX_M = 50.0;

    /** Смещение углового узла наружу от выпуклой оболочки препятствия (м). */
    private static final double CORNER_OFFSET_M = 10.0;

    /** Радиус поиска соседних узлов видимости (target+corner). */
    private static final double CORNER_CORNER_MAX_M = 300.0;

    /** Радиус поиска сетевых точек вокруг углового узла.
     *  Ограничен 500 м: если угол не дотянулся до сети за 500 м,
     *  дальнейший поиск даст только шум и замедление. */
    private static final double CORNER_NETWORK_MAX_M = 500.0;

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final ObstacleChecker obstacleChecker;
    private final CoordinateTransformer coordinateTransformer;
    private final RoutingConfig config;
    private final OksPolygonIndex oksPolygonIndex;

    public static class Edge {
        public String fromId;
        public String toId;
        public Coordinate fromCoordinateUtm;
        public Coordinate toCoordinateUtm;
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
        List<Node> chamberNodes = new ArrayList<>();
        for (GeoObject obj : nodes) {
            if (obj.getGeometry() == null) continue;
            Coordinate wgs84 = obj.getGeometry().getCoordinate();
            Geometry utmGeom = coordinateTransformer.toUtm37n(obj.getGeometry());
            if (utmGeom == null) continue;
            Coordinate utm = utmGeom.getCoordinate();
            Node n = new Node(obj.getId(), wgs84, utm, true, false);
            targetNodes.add(n);
            if ("heat_chamber".equals(obj.getObjectType())) {
                chamberNodes.add(n);
            }
        }

        // 2. Точки существующих сетей + рёбра «сеть ↔ сеть»
        List<Node> networkNodes = new ArrayList<>();
        List<Edge> edgesNetworkNetwork = new ArrayList<>();
        int networkPointCounter = 0;

        for (GeoObject net : existingNetworks) {
            if (net.getGeometry() == null) continue;
            Geometry utmGeom = coordinateTransformer.toUtm37n(net.getGeometry());
            if (utmGeom == null) continue;

            Coordinate[] coords = utmGeom.getCoordinates();
            List<Coordinate> pointsForNetwork = new ArrayList<>();

            for (int i = 0; i < coords.length - 1; i++) {
                Coordinate a = coords[i];
                Coordinate b = coords[i + 1];
                pointsForNetwork.add(a);

                double segLen = a.distance(b);
                int steps = (int) Math.ceil(segLen / config.getNetworkSplitStep());
                for (int s = 1; s < steps; s++) {
                    double t = (double) s / steps;
                    pointsForNetwork.add(new Coordinate(
                            a.x + t * (b.x - a.x),
                            a.y + t * (b.y - a.y)));
                }
            }
            pointsForNetwork.add(coords[coords.length - 1]);

            List<Node> chainNodes = new ArrayList<>();
            for (Coordinate point : pointsForNetwork) {
                String netPointId = "net_" + net.getId() + "_" + (networkPointCounter++);
                Node n = new Node(netPointId, point, point, true, true);
                networkNodes.add(n);
                chainNodes.add(n);
            }

            for (int i = 0; i < chainNodes.size() - 1; i++) {
                Node a = chainNodes.get(i);
                Node b = chainNodes.get(i + 1);
                double dist = a.coordinateUtm.distance(b.coordinateUtm);
                if (dist < 0.01) continue;

                LineString line = geometryFactory.createLineString(
                        new Coordinate[]{a.coordinateUtm, b.coordinateUtm});
                line.setSRID(32637);

                Edge edge = new Edge(a.id, b.id,
                        a.coordinateUtm, b.coordinateUtm,
                        line, dist, 0.0, "base", 1.0, diameter);
                edgesNetworkNetwork.add(edge);
            }
        }

        log.info("Целевых узлов: {}, точек сетей: {}, рёбер сеть-сеть: {}",
                targetNodes.size(), networkNodes.size(), edgesNetworkNetwork.size());

        // 3. Предвычисленные препятствия + STRtree
        STRtree obstacleSpatialIndex = new STRtree();
        List<PreparedObstacle> preparedObstacles = new ArrayList<>();

        for (GeoObject obstacle : obstacles) {
            String type = obstacle.getRestrictionType();
            if (type == null || obstacle.getGeometry() == null) continue;
            Geometry utmGeom = coordinateTransformer.toUtm37n(obstacle.getGeometry());
            if (utmGeom == null) continue;
            boolean forbidden = obstacleChecker.isForbidden(type);
            PreparedObstacle po = new PreparedObstacle(type, utmGeom, forbidden);
            preparedObstacles.add(po);
            obstacleSpatialIndex.insert(po.envelope, po);
        }
        obstacleSpatialIndex.build();
        log.info("Подготовлено препятствий: {}", preparedObstacles.size());

        // 4. STRtree для точек сети
        STRtree networkIndex = new STRtree();
        for (Node node : networkNodes) {
            networkIndex.insert(new Envelope(node.coordinateUtm), node);
        }
        networkIndex.build();

        double maxEdgeLength = config.getMaxEdgeLength();

        log.info("MAX_EDGE_LENGTH = {} м, CHAMBER_TO_NETWORK_MAX_M = {} м",
                maxEdgeLength, CHAMBER_TO_NETWORK_MAX_M);

        // 5. Рёбра: целевой ↔ точка сети
        ConcurrentLinkedQueue<Edge> edgesTargetNetwork = new ConcurrentLinkedQueue<>();
        targetNodes.parallelStream().forEach(from -> {
            Envelope searchEnv = new Envelope(
                    from.coordinateUtm.x - maxEdgeLength,
                    from.coordinateUtm.x + maxEdgeLength,
                    from.coordinateUtm.y - maxEdgeLength,
                    from.coordinateUtm.y + maxEdgeLength);

            List<?> candidates = networkIndex.query(searchEnv);
            for (Object obj : candidates) {
                Node to = (Node) obj;
                if (from.coordinateUtm.distance(to.coordinateUtm) > maxEdgeLength) continue;

                Edge edge = tryBuildEdge(from, to, obstacleSpatialIndex, diameter);
                if (edge != null) edgesTargetNetwork.add(edge);
            }
        });
        log.info("Рёбер целевой-сеть: {}", edgesTargetNetwork.size());

        // 6. Рёбра: целевой ↔ целевой
        ConcurrentLinkedQueue<Edge> edgesTargetTarget = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < targetNodes.size(); i++) {
            Node from = targetNodes.get(i);
            for (int j = i + 1; j < targetNodes.size(); j++) {
                Node to = targetNodes.get(j);
                if (from.coordinateUtm.distance(to.coordinateUtm) > maxEdgeLength) continue;

                Edge edge = tryBuildEdge(from, to, obstacleSpatialIndex, diameter);
                if (edge != null) edgesTargetTarget.add(edge);
            }
        }
        log.info("Рёбер целевой-целевой: {}", edgesTargetTarget.size());

        // 7. Рёбра «существующая камера ↔ точка сети»
        ConcurrentLinkedQueue<Edge> edgesChamberNetwork = new ConcurrentLinkedQueue<>();
        for (Node chamber : chamberNodes) {
            Envelope searchEnv = new Envelope(
                    chamber.coordinateUtm.x - CHAMBER_TO_NETWORK_MAX_M,
                    chamber.coordinateUtm.x + CHAMBER_TO_NETWORK_MAX_M,
                    chamber.coordinateUtm.y - CHAMBER_TO_NETWORK_MAX_M,
                    chamber.coordinateUtm.y + CHAMBER_TO_NETWORK_MAX_M);

            List<?> candidates = networkIndex.query(searchEnv);
            for (Object obj : candidates) {
                Node netPoint = (Node) obj;
                double dist = chamber.coordinateUtm.distance(netPoint.coordinateUtm);
                if (dist > CHAMBER_TO_NETWORK_MAX_M) continue;

                LineString line = geometryFactory.createLineString(
                        new Coordinate[]{chamber.coordinateUtm, netPoint.coordinateUtm});
                line.setSRID(32637);

                Edge edge = new Edge(chamber.id, netPoint.id,
                        chamber.coordinateUtm, netPoint.coordinateUtm,
                        line, dist, 0.0, "base", 1.0, diameter);
                edgesChamberNetwork.add(edge);
            }
        }
        log.info("Рёбер камера-сеть: {}", edgesChamberNetwork.size());

        // 8. Corner-узлы
        List<Node> cornerNodes = new ArrayList<>();
        int cornerCounter = 0;
        for (PreparedObstacle po : preparedObstacles) {
            Geometry ref;
            Geometry geom = po.geometryUtm;
            if (geom instanceof Polygon || geom instanceof MultiPolygon) {
                ref = geom.convexHull();
            } else if (geom instanceof LineString) {
                ref = geom;
            } else {
                continue;
            }

            Coordinate centroid = ref.getCentroid().getCoordinate();
            for (Coordinate c : ref.getCoordinates()) {
                double dx = c.x - centroid.x;
                double dy = c.y - centroid.y;
                double len = Math.hypot(dx, dy);
                if (len < 1e-6) continue;
                double ox = c.x + CORNER_OFFSET_M * dx / len;
                double oy = c.y + CORNER_OFFSET_M * dy / len;
                Coordinate oc = new Coordinate(ox, oy);
                cornerNodes.add(new Node("tn_" + (cornerCounter++), oc, oc, false, false));
            }
        }
        log.info("[Граф] Угловых узлов: {}", cornerNodes.size());

        // 9. Рёбра видимости: target+corner ↔ target+corner
        List<Node> visibilityNodes = new ArrayList<>(targetNodes);
        visibilityNodes.addAll(cornerNodes);

        STRtree visIndex = new STRtree();
        for (Node n : visibilityNodes) {
            visIndex.insert(new Envelope(n.coordinateUtm), n);
        }
        visIndex.build();

        List<Edge> edgesVisibility = new ArrayList<>();
        for (Node a : visibilityNodes) {
            Envelope env = new Envelope(
                    a.coordinateUtm.x - CORNER_CORNER_MAX_M,
                    a.coordinateUtm.x + CORNER_CORNER_MAX_M,
                    a.coordinateUtm.y - CORNER_CORNER_MAX_M,
                    a.coordinateUtm.y + CORNER_CORNER_MAX_M);
            List<?> candidates = visIndex.query(env);
            for (Object obj : candidates) {
                Node b = (Node) obj;
                if (a.id.compareTo(b.id) >= 0) continue;
                if (a.coordinateUtm.distance(b.coordinateUtm) > CORNER_CORNER_MAX_M) continue;
                Edge e = tryBuildEdge(a, b, obstacleSpatialIndex, diameter);
                if (e != null) edgesVisibility.add(e);
            }
        }
        log.info("[Граф] Рёбер видимости (target+corner): {}", edgesVisibility.size());

        // 10. Рёбра: corner ↔ точка сети (параллельно)
        ConcurrentLinkedQueue<Edge> edgesCornerNetwork = new ConcurrentLinkedQueue<>();
        cornerNodes.parallelStream().forEach(corner -> {
            Envelope env = new Envelope(
                    corner.coordinateUtm.x - CORNER_NETWORK_MAX_M,
                    corner.coordinateUtm.x + CORNER_NETWORK_MAX_M,
                    corner.coordinateUtm.y - CORNER_NETWORK_MAX_M,
                    corner.coordinateUtm.y + CORNER_NETWORK_MAX_M);
            List<?> candidates = networkIndex.query(env);
            for (Object obj : candidates) {
                Node netPoint = (Node) obj;
                if (corner.coordinateUtm.distance(netPoint.coordinateUtm) > CORNER_NETWORK_MAX_M) continue;
                Edge e = tryBuildEdge(corner, netPoint, obstacleSpatialIndex, diameter);
                if (e != null) edgesCornerNetwork.add(e);
            }
        });
        log.info("[Граф] Рёбер угол-сеть: {}", edgesCornerNetwork.size());

        // 11. Объединение
        List<Edge> allEdges = new ArrayList<>();
        allEdges.addAll(edgesNetworkNetwork);
        allEdges.addAll(edgesTargetNetwork);
        allEdges.addAll(edgesTargetTarget);
        allEdges.addAll(edgesChamberNetwork);
        allEdges.addAll(edgesVisibility);
        allEdges.addAll(edgesCornerNetwork);

        log.info("Итого рёбер: {}, время построения графа: {} мс",
                allEdges.size(), System.currentTimeMillis() - startTime);
        return allEdges;
    }

    private Edge tryBuildEdge(Node from, Node to, STRtree obstacleSpatialIndex, int diameter) {
        LineString line = geometryFactory.createLineString(
                new Coordinate[]{from.coordinateUtm, to.coordinateUtm});
        line.setSRID(32637);

        if (!isValidEdge(line, obstacleSpatialIndex, diameter, from, to)) {
            return null;
        }

        double dist = from.coordinateUtm.distance(to.coordinateUtm);
        double kspec = calculateMaxKspec(line, obstacleSpatialIndex);
        String layingMethod = (kspec > 1.0) ? "special" : "base";
        double costPerMeter = getCostPerMeter(diameter);
        double cost = dist * costPerMeter * kspec;

        return new Edge(from.id, to.id,
                from.coordinateUtm, to.coordinateUtm,
                line, dist, cost, layingMethod, kspec, diameter);
    }

    private boolean isValidEdge(LineString line, STRtree obstacleSpatialIndex,
                                int diameter, Node from, Node to) {
        List<?> closeObstacles = obstacleSpatialIndex.query(line.getEnvelopeInternal());

        for (Object obj : closeObstacles) {
            PreparedObstacle po = (PreparedObstacle) obj;

            if ("oks".equals(po.restrictionType)) {
                if (line.intersects(po.geometryUtm)) {
                    if (isAllowedFinalSegmentForOks(from, to, po.geometryUtm)) {
                        continue;
                    }
                    return false;
                }
                if (!obstacleChecker.isDistanceOk(line, po.geometryUtm,
                        po.restrictionType, diameter)) {
                    return false;
                }
                continue;
            }

            boolean intersects = line.intersects(po.geometryUtm);

            if (po.forbidden) {
                if (intersects) {
                    return false;
                }
                if (!obstacleChecker.isDistanceOk(line, po.geometryUtm,
                        po.restrictionType, diameter)) {
                    return false;
                }
            } else {
                if (intersects) {
                    if (!obstacleChecker.isAngleOk(line, po.geometryUtm, po.restrictionType)) {
                        return false;
                    }
                } else {
                    if (!obstacleChecker.isDistanceOk(line, po.geometryUtm,
                            po.restrictionType, diameter)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean isAllowedFinalSegmentForOks(Node from, Node to, Geometry oksPolygonUtm) {
        Point fromPoint = geometryFactory.createPoint(from.coordinateUtm);
        Point toPoint = geometryFactory.createPoint(to.coordinateUtm);

        boolean fromInside = oksPolygonUtm.contains(fromPoint)
                || oksPolygonUtm.distance(fromPoint) <= 1.0;
        boolean toInside = oksPolygonUtm.contains(toPoint);

        if (fromInside == toInside) return false;

        String oksId = fromInside ? from.id : to.id;

        return oksPolygonIndex.isOwnPolygon(oksId, oksPolygonUtm);
    }

    private double calculateMaxKspec(LineString line, STRtree obstacleSpatialIndex) {
        double maxKspec = 1.0;
        List<?> closeObstacles = obstacleSpatialIndex.query(line.getEnvelopeInternal());

        for (Object obj : closeObstacles) {
            PreparedObstacle po = (PreparedObstacle) obj;
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