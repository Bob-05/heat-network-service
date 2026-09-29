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
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import ru.hackathon.heatnetworkservice.config.RoutingConfig;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class GraphBuilder {

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
        final boolean special;

        PreparedObstacle(String restrictionType, Geometry geometryUtm,
                         boolean forbidden, boolean special) {
            this.restrictionType = restrictionType;
            this.geometryUtm = geometryUtm;
            this.envelope = geometryUtm.getEnvelopeInternal();
            this.forbidden = forbidden;
            this.special = special;
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
            boolean special   = obstacleChecker.isSpecial(type);
            PreparedObstacle po = new PreparedObstacle(type, utmGeom, forbidden, special);
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
        double chamberToNetworkMax = config.getChamberToNetworkMax();
        double cornerOffset = config.getCornerOffset();
        double cornerCornerMax = config.getCornerCornerMax();
        double cornerNetworkMax = config.getCornerNetworkMax();

        // Разделяемые узлы на границах спецпроходов (для повторного использования
        // между потоками, поскольку parallelStream может вызывать tryBuildEdges
        // из нескольких потоков).
        Map<String, Node> splitNodeCache = new ConcurrentHashMap<>();
        AtomicInteger splitNodeCounter = new AtomicInteger(0);

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
                edgesTargetNetwork.addAll(
                        tryBuildEdges(from, to, obstacleSpatialIndex, diameter,
                                splitNodeCache, splitNodeCounter));
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
                edgesTargetTarget.addAll(
                        tryBuildEdges(from, to, obstacleSpatialIndex, diameter,
                                splitNodeCache, splitNodeCounter));
            }
        }
        log.info("Рёбер целевой-целевой: {}", edgesTargetTarget.size());

        // 7. Рёбра «существующая камера ↔ точка сети»
        ConcurrentLinkedQueue<Edge> edgesChamberNetwork = new ConcurrentLinkedQueue<>();
        for (Node chamber : chamberNodes) {
            Envelope searchEnv = new Envelope(
                    chamber.coordinateUtm.x - chamberToNetworkMax,
                    chamber.coordinateUtm.x + chamberToNetworkMax,
                    chamber.coordinateUtm.y - chamberToNetworkMax,
                    chamber.coordinateUtm.y + chamberToNetworkMax);

            List<?> candidates = networkIndex.query(searchEnv);
            for (Object obj : candidates) {
                Node netPoint = (Node) obj;
                double dist = chamber.coordinateUtm.distance(netPoint.coordinateUtm);
                if (dist > chamberToNetworkMax) continue;

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

        // 8. Corner-узлы вокруг препятствий
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
                double ox = c.x + cornerOffset * dx / len;
                double oy = c.y + cornerOffset * dy / len;
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
                    a.coordinateUtm.x - cornerCornerMax,
                    a.coordinateUtm.x + cornerCornerMax,
                    a.coordinateUtm.y - cornerCornerMax,
                    a.coordinateUtm.y + cornerCornerMax);
            List<?> candidates = visIndex.query(env);
            for (Object obj : candidates) {
                Node b = (Node) obj;
                if (a.id.compareTo(b.id) >= 0) continue;
                if (a.coordinateUtm.distance(b.coordinateUtm) > cornerCornerMax) continue;
                edgesVisibility.addAll(
                        tryBuildEdges(a, b, obstacleSpatialIndex, diameter,
                                splitNodeCache, splitNodeCounter));
            }
        }
        log.info("[Граф] Рёбер видимости (target+corner): {}", edgesVisibility.size());

        // 10. Рёбра: corner ↔ точка сети (параллельно)
        ConcurrentLinkedQueue<Edge> edgesCornerNetwork = new ConcurrentLinkedQueue<>();
        cornerNodes.parallelStream().forEach(corner -> {
            Envelope env = new Envelope(
                    corner.coordinateUtm.x - cornerNetworkMax,
                    corner.coordinateUtm.x + cornerNetworkMax,
                    corner.coordinateUtm.y - cornerNetworkMax,
                    corner.coordinateUtm.y + cornerNetworkMax);
            List<?> candidates = networkIndex.query(env);
            for (Object obj : candidates) {
                Node netPoint = (Node) obj;
                if (corner.coordinateUtm.distance(netPoint.coordinateUtm) > cornerNetworkMax) continue;
                edgesCornerNetwork.addAll(
                        tryBuildEdges(corner, netPoint, obstacleSpatialIndex, diameter,
                                splitNodeCache, splitNodeCounter));
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

    /**
     * Создаёт одно или несколько рёбер между from и to.
     * Если ребро пересекает «специальные» ограничения, оно делится
     * на границах этих ограничений (ТЗ п.8.3). K_спец применяется
     * только к длине того под-ребра, которое фактически пересекает
     * данное ограничение.
     */
    private List<Edge> tryBuildEdges(Node from, Node to,
                                     STRtree obstacleSpatialIndex,
                                     int diameter,
                                     Map<String, Node> splitNodeCache,
                                     AtomicInteger splitNodeCounter) {
        LineString line = geometryFactory.createLineString(
                new Coordinate[]{from.coordinateUtm, to.coordinateUtm});
        line.setSRID(32637);

        if (!isValidEdge(line, obstacleSpatialIndex, diameter, from, to)) {
            return Collections.emptyList();
        }

        // Разбиваем ребро на границах специальных ограничений
        List<LineString> parts = splitAtSpecialObstacleBoundaries(line, obstacleSpatialIndex);

        List<Edge> result = new ArrayList<>(parts.size());
        Node currentFrom = from;

        for (int i = 0; i < parts.size(); i++) {
            LineString part = parts.get(i);
            Coordinate[] partCoords = part.getCoordinates();
            if (partCoords.length < 2) continue;

            Node currentTo;
            if (i < parts.size() - 1) {
                Coordinate splitCoord = partCoords[partCoords.length - 1];
                String key = Math.round(splitCoord.x * 1000) + "_"
                        + Math.round(splitCoord.y * 1000);
                currentTo = splitNodeCache.computeIfAbsent(key, k -> {
                    String id = "tn_split_" + splitNodeCounter.getAndIncrement();
                    return new Node(id, splitCoord, splitCoord, false, false);
                });
            } else {
                currentTo = to;
            }

            double partLength = part.getLength();
            double kspec = calculateKspecForPart(part, obstacleSpatialIndex);
            String layingMethod = (kspec > 1.0) ? "special" : "base";
            double costPerMeter = getCostPerMeter(diameter);
            double cost = partLength * costPerMeter * kspec;

            result.add(new Edge(
                    currentFrom.id, currentTo.id,
                    currentFrom.coordinateUtm, currentTo.coordinateUtm,
                    part, partLength, cost,
                    layingMethod, kspec, diameter));

            currentFrom = currentTo;
        }

        return result;
    }

    /**
     * Разбивает ребро на под-рёбра в точках пересечения границ
     * специальных ограничений. Точки берутся из пересечения ребра
     * с BOUNDARY полигонов и с самими линейными ограничениями.
     *
     * ТЗ п.8.3: «На границах специального прохода участок делится.
     * Если граница не совпадает с тепловой камерой или точкой
     * подключения ОКС, создаётся технический узел (technical_node)».
     *
     * Реализация:
     *   1. Для каждого специального (не forbidden) ограничения,
     *      которое реально пересекает ребро, находим точки пересечения
     *      геометрии ребра с границей ограничения.
     *   2. Проецируем эти точки на ребро (получаем расстояние от начала
     *      вдоль ребра) через LengthIndexedLine.project(Coordinate).
     *   3. Сортируем расстояния и режем ребро на под-сегменты
     *      через LengthIndexedLine.extractLine(from, to).
     *
     * Если точек разбиения нет — возвращаем одно ребро без изменений.
     */
    private List<LineString> splitAtSpecialObstacleBoundaries(LineString line,
                                                              STRtree obstacleSpatialIndex) {
        double totalLength = line.getLength();
        if (totalLength < 1e-3) {
            return Collections.singletonList(line);
        }

        List<?> obstacles = obstacleSpatialIndex.query(line.getEnvelopeInternal());
        Set<Double> splitDistances = new HashSet<>();
        LengthIndexedLine indexedLine = new LengthIndexedLine(line);

        for (Object obj : obstacles) {
            PreparedObstacle po = (PreparedObstacle) obj;

            // Нас интересуют только специальные (не запрещённые)
            // ограничения — именно на их границах ставится technical_node.
            if (po.forbidden || !po.special) continue;
            if (!line.intersects(po.geometryUtm)) continue;

            // Для полигонов используем только границу — нам нужны точки
            // входа/выхода, а не весь участок внутри полигона.
            Geometry ref = po.geometryUtm;
            if (ref instanceof Polygon || ref instanceof MultiPolygon) {
                ref = ref.getBoundary();
            }

            Geometry intersection = line.intersection(ref);
            if (intersection == null || intersection.isEmpty()) continue;

            for (int i = 0; i < intersection.getNumGeometries(); i++) {
                Geometry g = intersection.getGeometryN(i);
                for (Coordinate c : g.getCoordinates()) {
                    try {
                        // LengthIndexedLine.project принимает Coordinate,
                        // а не Point. Возвращает расстояние от начала
                        // линии вдоль её геометрии.
                        double d = indexedLine.project(c);
                        if (d > 1e-3 && d < totalLength - 1e-3) {
                            // Округляем до мм, чтобы схлопнуть дубликаты,
                            // которые могут возникнуть из-за перекрывающихся
                            // ограничений (наложение спецпроходов, ТЗ п.8.3).
                            splitDistances.add(Math.round(d * 1000.0) / 1000.0);
                        }
                    } catch (Exception ignore) {
                        // Точка вне диапазона ребра — игнорируем.
                        // Такое может случиться при вырожденных геометриях.
                    }
                }
            }
        }

        // Нет точек разбиения — возвращаем исходное ребро как есть.
        if (splitDistances.isEmpty()) {
            return Collections.singletonList(line);
        }

        // Сортируем расстояния по возрастанию вдоль ребра.
        List<Double> sorted = new ArrayList<>(splitDistances);
        Collections.sort(sorted);

        // Режем ребро на под-сегменты между последовательными точками.
        List<LineString> result = new ArrayList<>();
        double prevD = 0.0;
        for (double d : sorted) {
            if (d - prevD < 1e-3) continue; // защита от очень близких точек
            Geometry piece = indexedLine.extractLine(prevD, d);
            if (piece instanceof LineString
                    && piece.getNumPoints() >= 2
                    && piece.getLength() > 1e-3) {
                result.add((LineString) piece);
            }
            prevD = d;
        }

        // Финальный кусок — от последней точки до конца ребра.
        if (totalLength - prevD > 1e-3) {
            Geometry piece = indexedLine.extractLine(prevD, totalLength);
            if (piece instanceof LineString
                    && piece.getNumPoints() >= 2
                    && piece.getLength() > 1e-3) {
                result.add((LineString) piece);
            }
        }

        // Страховка: если по какой-то причине не получилось ни одного
        // под-сегмента — возвращаем исходное ребро.
        if (result.isEmpty()) {
            return Collections.singletonList(line);
        }
        return result;
    }

    /**
     * Считает K_спец для под-ребра. Если под-ребро лежит внутри
     * нескольких специальных ограничений (ТЗ п.8.3 — «наложение»),
     * берётся МАКСИМУМ соответствующих коэффициентов.
     * Коэффициенты не суммируются и не перемножаются.
     */
    private double calculateKspecForPart(LineString part, STRtree obstacleSpatialIndex) {
        double maxKspec = 1.0;
        List<?> closeObstacles = obstacleSpatialIndex.query(part.getEnvelopeInternal());

        for (Object obj : closeObstacles) {
            PreparedObstacle po = (PreparedObstacle) obj;
            if (po.forbidden || !po.special) continue;

            // Проверяем ФАКТИЧЕСКОЕ пересечение с ненулевой длиной:
            // касание границы концом ребра — не специальный проход.
            Geometry inter = part.intersection(po.geometryUtm);
            if (inter == null || inter.isEmpty()) continue;
            if (inter.getDimension() < 1 && inter.getLength() < 1e-3) continue;

            double kspec = obstacleChecker.getKspec(po.restrictionType);
            if (kspec > maxKspec) maxKspec = kspec;
        }
        return maxKspec;
    }

    /**
     * Полная проверка ребра на допустимость по всем препятствиям.
     * Не применяет K_спец — только валидация.
     */
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

    /**
     * ТЗ п.2.2 + разъяснение 3: один финальный прямой участок
     * от ближайшей к точке границы собственного полигона ОКС
     * до самой точки. Оба конца проверяем с одинаковым допуском 1 м,
     * поскольку из-за погрешности WGS84→UTM37N точка ОКС может
     * оказаться ровно на границе.
     */
    private boolean isAllowedFinalSegmentForOks(Node from, Node to, Geometry oksPolygonUtm) {
        Point fromPoint = geometryFactory.createPoint(from.coordinateUtm);
        Point toPoint = geometryFactory.createPoint(to.coordinateUtm);

        double tol = 1.0;
        boolean fromInside = oksPolygonUtm.contains(fromPoint)
                || oksPolygonUtm.distance(fromPoint) <= tol;
        boolean toInside = oksPolygonUtm.contains(toPoint)
                || oksPolygonUtm.distance(toPoint) <= tol;

        // Ровно один конец — в полигоне (иначе это либо полностью
        // внешнее, либо полностью внутреннее ребро).
        if (fromInside == toInside) return false;

        String oksId = fromInside ? from.id : to.id;
        return oksPolygonIndex.isOwnPolygon(oksId, oksPolygonUtm);
    }

    private double getCostPerMeter(int diameter) {
        switch (diameter) {
            case 50:   return 74023;
            case 65:   return 78631;
            case 80:   return 83530;
            case 100:  return 89748;
            case 125:  return 97275;
            case 150:  return 105507;
            case 200:  return 120275;
            case 250:  return 135323;
            case 300:  return 150022;
            case 400:  return 190299;
            case 500:  return 224137;
            case 600:  return 264790;
            case 700:  return 324298;
            case 800:  return 325996;
            case 900:  return 327693;
            case 1000: return 418777;
            case 1200: return 428074;
            case 1400: return 683417;
            default:   return 150022;
        }
    }
}