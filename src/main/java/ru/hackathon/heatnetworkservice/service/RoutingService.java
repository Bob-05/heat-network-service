package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.geometry.GraphBuilder;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingService {

    private final GraphBuilder graphBuilder;
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
        public boolean endIsChamber;
        public List<GraphBuilder.Edge> edges;
        public double totalLength;
        public double totalCost;

        public Route(String oksId, Double oksFlowTph, String endNodeId, boolean endIsChamber,
                     List<GraphBuilder.Edge> edges) {
            this.oksId = oksId;
            this.oksFlowTph = oksFlowTph;
            this.endNodeId = endNodeId;
            this.endIsChamber = endIsChamber;
            this.edges = edges;
            this.totalLength = edges.stream().mapToDouble(e -> e.length).sum();
            this.totalCost = edges.stream().mapToDouble(e -> e.cost).sum();
        }
    }

    public List<Route> buildRoutes(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            List<GeoObject> existingNetworks,
            int diameter,
            WeightType weightType
    ) {
        List<GeoObject> allNodes = new ArrayList<>();
        allNodes.addAll(oksPoints);
        allNodes.addAll(chambers);

        List<GraphBuilder.Edge> edges = graphBuilder.buildGraph(
                allNodes, oksPoints, obstacles, existingNetworks, diameter);

        return findRoutes(edges, oksPoints, chambers, weightType);
    }

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

        // Множества ID целевых узлов
        Set<String> chamberIds = new HashSet<>();
        for (GeoObject chamber : chambers) {
            chamberIds.add(chamber.getId());
        }

        // Точки сетей: ID начинается с "net_"
        Set<String> networkPointIds = new HashSet<>();
        for (String nodeId : adjacency.keySet()) {
            if (nodeId.startsWith("net_")) {
                networkPointIds.add(nodeId);
            }
        }

        List<Route> routes = new ArrayList<>();
        for (GeoObject oks : oksPoints) {
            Route route = findShortestRoute(oks, chamberIds, networkPointIds, adjacency, weightType);
            if (route != null) {
                routes.add(route);
            } else {
                log.warn("Маршрут для ОКС {} не найден", oks.getId());
            }
        }

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

    private Route findShortestRoute(
            GeoObject oks,
            Set<String> chamberIds,
            Set<String> networkPointIds,
            Map<String, List<GraphBuilder.Edge>> adjacency,
            WeightType weightType
    ) {
        String startId = oks.getId();

        Map<String, Double> distances = new HashMap<>();
        Map<String, GraphBuilder.Edge> predecessors = new HashMap<>();
        Set<String> visited = new HashSet<>();

        PriorityQueue<String> queue = new PriorityQueue<>(
                Comparator.comparingDouble(id -> distances.getOrDefault(id, Double.MAX_VALUE))
        );

        distances.put(startId, 0.0);
        queue.add(startId);

        String endNode = null;

        while (!queue.isEmpty()) {
            String current = queue.poll();

            if (visited.contains(current)) continue;
            visited.add(current);

            // Цель: камера или точка сети
            if (!current.equals(startId)
                    && (chamberIds.contains(current) || networkPointIds.contains(current))) {
                endNode = current;
                break;
            }

            List<GraphBuilder.Edge> neighbors = adjacency.getOrDefault(current, Collections.emptyList());
            for (GraphBuilder.Edge edge : neighbors) {
                String neighbor = edge.fromId.equals(current) ? edge.toId : edge.fromId;
                if (visited.contains(neighbor)) continue;

                double weight = getEdgeWeight(edge, weightType);
                double newDistance = distances.get(current) + weight;

                if (newDistance < distances.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    distances.put(neighbor, newDistance);
                    predecessors.put(neighbor, edge);
                    queue.add(neighbor);
                }
            }
        }

        if (endNode == null) {
            return null;
        }

        List<GraphBuilder.Edge> path = new ArrayList<>();
        String current = endNode;
        while (!current.equals(startId)) {
            GraphBuilder.Edge edge = predecessors.get(current);
            if (edge == null) break;
            path.add(0, edge);
            current = edge.fromId.equals(current) ? edge.toId : edge.fromId;
        }

        boolean endIsChamber = chamberIds.contains(endNode);
        return new Route(startId, oks.getFlowTph(), endNode, endIsChamber, path);
    }
}