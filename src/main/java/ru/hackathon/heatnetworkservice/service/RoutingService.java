package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.geometry.GraphBuilder;
import ru.hackathon.heatnetworkservice.geometry.OksPolygonIndex;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingService {

    private final GraphBuilder graphBuilder;
    private final OksPolygonIndex oksPolygonIndex;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public enum WeightType {
        LENGTH,
        COST,
        SCORE
    }

    public static class Route {
        public String oksId;
        public Double oksFlowTph;
        public String endNodeId;
        /** true, если маршрут заканчивается в существующей heat_chamber (ТЗ п.2.4). */
        public boolean endIsChamber;
        public Coordinate endCoordinateUtm;
        public List<GraphBuilder.Edge> edges;
        public double totalLength;
        public double totalCost;

        public Route(String oksId, Double oksFlowTph, String endNodeId,
                     boolean endIsChamber, Coordinate endCoordinateUtm,
                     List<GraphBuilder.Edge> edges) {
            this.oksId = oksId;
            this.oksFlowTph = oksFlowTph;
            this.endNodeId = endNodeId;
            this.endIsChamber = endIsChamber;
            this.endCoordinateUtm = endCoordinateUtm;
            this.edges = edges;
            this.totalLength = edges.stream().mapToDouble(e -> e.length).sum();
            this.totalCost = edges.stream().mapToDouble(e -> e.cost).sum();
        }
    }

    public List<GraphBuilder.Edge> buildGraphOnce(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            List<GeoObject> oksRestrictions,
            List<GeoObject> existingNetworks,
            int diameter
    ) {
        oksPolygonIndex.build(oksPoints, oksRestrictions);

        List<GeoObject> allNodes = new ArrayList<>();
        allNodes.addAll(oksPoints);
        allNodes.addAll(chambers);

        return graphBuilder.buildGraph(
                allNodes, oksPoints, obstacles, existingNetworks, diameter);
    }

    public List<Route> buildRoutes(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            List<GeoObject> oksRestrictions,
            List<GeoObject> existingNetworks,
            int diameter,
            WeightType weightType
    ) {
        List<GraphBuilder.Edge> edges = buildGraphOnce(
                oksPoints, chambers, obstacles, oksRestrictions,
                existingNetworks, diameter);
        return findRoutes(edges, oksPoints, chambers, weightType);
    }

    /**
     * Поиск маршрутов до точек сети ИЛИ существующих heat_chamber.
     *
     * ТЗ п.2.1: разветвления только в тепловых камерах → ОКС запрещены
     * как транзитные узлы.
     * ТЗ п.2.4: если точка присоединения не далее 10 м от существующей
     * heat_chamber — используется эта камера. Если маршрут заканчивается
     * прямо в камере, условие заведомо выполняется.
     */
    public List<Route> findRoutes(
            List<GraphBuilder.Edge> edges,
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            WeightType weightType
    ) {
        log.info("Маршрутизация (вес={}): {} ОКС, {} камер",
                weightType, oksPoints.size(), chambers.size());

        if (edges.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<GraphBuilder.Edge>> adjacency = buildAdjacency(edges);

        Set<String> oksIds = oksPoints.stream()
                .map(GeoObject::getId)
                .collect(Collectors.toSet());

        Set<String> chamberIds = chambers.stream()
                .map(GeoObject::getId)
                .collect(Collectors.toSet());

        Set<String> targetIds = new HashSet<>();
        targetIds.addAll(adjacency.keySet().stream()
                .filter(id -> id.startsWith("net_"))
                .collect(Collectors.toSet()));
        targetIds.addAll(chamberIds);

        List<Route> routes = oksPoints.parallelStream()
                .map(oks -> {
                    Route route = dijkstra(oks, targetIds, oksIds, chamberIds,
                            adjacency, weightType);
                    if (route == null) {
                        log.warn("Маршрут для ОКС {} не найден", oks.getId());
                    }
                    return route;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.info("Построено маршрутов: {} из {} (вес={})",
                routes.size(), oksPoints.size(), weightType);
        return routes;
    }

    private Map<String, List<GraphBuilder.Edge>> buildAdjacency(List<GraphBuilder.Edge> edges) {
        Map<String, List<GraphBuilder.Edge>> adjacency = new HashMap<>();
        for (GraphBuilder.Edge edge : edges) {
            adjacency.computeIfAbsent(edge.fromId, k -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.toId, k -> new ArrayList<>()).add(edge);
        }
        return adjacency;
    }

    private double getEdgeWeight(GraphBuilder.Edge edge, WeightType weightType) {
        switch (weightType) {
            case LENGTH:
                return edge.length;
            case COST:
                return edge.cost;
            case SCORE:
                return 0.7 * (edge.cost / 25_000_000.0) + 0.3 * (edge.length / 100.0);
            default:
                return edge.cost;
        }
    }

    private Route dijkstra(
            GeoObject oks,
            Set<String> targetIds,
            Set<String> forbiddenIntermediateIds,
            Set<String> chamberIds,
            Map<String, List<GraphBuilder.Edge>> adjacency,
            WeightType weightType
    ) {
        String startId = oks.getId();
        if (targetIds.isEmpty()) return null;

        Map<String, Double> distances = new HashMap<>();
        Map<String, GraphBuilder.Edge> predecessors = new HashMap<>();
        Set<String> visited = new HashSet<>();

        PriorityQueue<String> queue = new PriorityQueue<>(
                Comparator.comparingDouble(id ->
                        distances.getOrDefault(id, Double.MAX_VALUE)));

        distances.put(startId, 0.0);
        queue.add(startId);

        String endNode = null;

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            if (!current.equals(startId) && targetIds.contains(current)) {
                endNode = current;
                break;
            }

            List<GraphBuilder.Edge> neighbors =
                    adjacency.getOrDefault(current, Collections.emptyList());
            for (GraphBuilder.Edge edge : neighbors) {
                String neighbor = edge.fromId.equals(current) ? edge.toId : edge.fromId;
                if (visited.contains(neighbor)) continue;

                // ТЗ п.2.1: разветвления только в тепловых камерах.
                // ОКС запрещены как транзитные узлы.
                if (forbiddenIntermediateIds.contains(neighbor)
                        && !targetIds.contains(neighbor)) {
                    continue;
                }

                double weight = getEdgeWeight(edge, weightType);
                double newDistance = distances.get(current) + weight;

                if (newDistance < distances.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    distances.put(neighbor, newDistance);
                    predecessors.put(neighbor, edge);
                    queue.add(neighbor);
                }
            }
        }

        if (endNode == null) return null;

        List<GraphBuilder.Edge> path = new ArrayList<>();
        String current = endNode;
        while (!current.equals(startId)) {
            GraphBuilder.Edge edge = predecessors.get(current);
            if (edge == null) break;
            path.add(0, edge);
            current = edge.fromId.equals(current) ? edge.toId : edge.fromId;
        }

        Coordinate endCoordinateUtm = null;
        if (!path.isEmpty()) {
            GraphBuilder.Edge lastEdge = path.get(path.size() - 1);
            if (lastEdge.toId.equals(endNode)) {
                endCoordinateUtm = lastEdge.toCoordinateUtm;
            } else {
                endCoordinateUtm = lastEdge.fromCoordinateUtm;
            }
        }

        boolean endIsChamber = chamberIds.contains(endNode);
        return new Route(startId, oks.getFlowTph(), endNode, endIsChamber,
                endCoordinateUtm, path);
    }
}