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

    /**
     * Тип веса для поиска кратчайшего пути.
     * - LENGTH: минимизируем длину маршрута.
     * - COST: минимизируем стоимость прокладки.
     * - SCORE: минимизируем итоговый показатель варианта.
     */
    public enum WeightType {
        LENGTH,
        COST,
        SCORE
    }

    /**
     * Результат маршрутизации — путь от ОКС до камеры.
     */
    public static class Route {
        public String oksId;
        public Double oksFlowTph;
        public String chamberId;
        public List<GraphBuilder.Edge> edges;
        public double totalLength;
        public double totalCost;

        public Route(String oksId, Double oksFlowTph, String chamberId, List<GraphBuilder.Edge> edges) {
            this.oksId = oksId;
            this.oksFlowTph = oksFlowTph;
            this.chamberId = chamberId;
            this.edges = edges;
            this.totalLength = edges.stream().mapToDouble(e -> e.length).sum();
            this.totalCost = edges.stream().mapToDouble(e -> e.cost).sum();
        }
    }

    /**
     * Строит маршруты для всех ОКС с указанным весом.
     * Граф передаётся снаружи — чтобы не строить его повторно.
     */
    public List<Route> findRoutes(
            List<GraphBuilder.Edge> edges,
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            WeightType weightType
    ) {
        log.info("Начинаем маршрутизацию (вес={}): {} ОКС, {} камер",
                weightType, oksPoints.size(), chambers.size());

        if (edges.isEmpty()) {
            log.warn("Граф пуст!");
            return Collections.emptyList();
        }

        // Строим список смежности
        Map<String, List<GraphBuilder.Edge>> adjacency = buildAdjacency(edges);

        // Множество ID камер
        Set<String> chamberIds = new HashSet<>();
        for (GeoObject chamber : chambers) {
            chamberIds.add(chamber.getId());
        }

        // Для каждой ОКС ищем путь
        List<Route> routes = new ArrayList<>();
        for (GeoObject oks : oksPoints) {
            Route route = findShortestRoute(oks, chamberIds, adjacency, weightType);
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

    /**
     * Удобный метод: строит граф и сразу ищет маршруты.
     * Использует ДУ для проверки препятствий (отступ oks).
     */
    public List<Route> buildRoutes(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            int diameter,
            WeightType weightType
    ) {
        List<GeoObject> allNodes = new ArrayList<>();
        allNodes.addAll(oksPoints);
        allNodes.addAll(chambers);

        List<GraphBuilder.Edge> edges = graphBuilder.buildGraph(allNodes, obstacles, diameter);
        return findRoutes(edges, oksPoints, chambers, weightType);
    }

    private Map<String, List<GraphBuilder.Edge>> buildAdjacency(List<GraphBuilder.Edge> edges) {
        Map<String, List<GraphBuilder.Edge>> adjacency = new HashMap<>();
        for (GraphBuilder.Edge edge : edges) {
            adjacency.computeIfAbsent(edge.fromId, k -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.toId, k -> new ArrayList<>()).add(edge);
        }
        return adjacency;
    }

    /**
     * Возвращает вес ребра для указанного типа.
     */
    private double getEdgeWeight(GraphBuilder.Edge edge, WeightType weightType) {
        switch (weightType) {
            case LENGTH:
                return edge.length;
            case COST:
                return edge.cost;
            case SCORE:
                // score = 0,7 · cost/25M + 0,3 · length/100
                return 0.7 * (edge.cost / 25_000_000.0) + 0.3 * (edge.length / 100.0);
            default:
                return edge.cost;
        }
    }

    /**
     * Алгоритм Дейкстры с учётом типа веса.
     */
    private Route findShortestRoute(
            GeoObject oks,
            Set<String> chamberIds,
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

        String closestChamber = null;

        while (!queue.isEmpty()) {
            String current = queue.poll();

            if (visited.contains(current)) continue;
            visited.add(current);

            if (chamberIds.contains(current)) {
                closestChamber = current;
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

        if (closestChamber == null) {
            return null;
        }

        // Восстанавливаем путь
        List<GraphBuilder.Edge> path = new ArrayList<>();
        String current = closestChamber;
        while (!current.equals(startId)) {
            GraphBuilder.Edge edge = predecessors.get(current);
            if (edge == null) break;
            path.add(0, edge);
            current = edge.fromId.equals(current) ? edge.toId : edge.fromId;
        }

        return new Route(startId, oks.getFlowTph(), closestChamber, path);
    }
}