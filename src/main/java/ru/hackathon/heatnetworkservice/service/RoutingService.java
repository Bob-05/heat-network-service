package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
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
     * Результат маршрутизации — путь от ОКС до камеры.
     */
    public static class Route {
        public String oksId;          // ID точки подключения ОКС
        public String chamberId;      // ID существующей камеры
        public List<GraphBuilder.Edge> edges; // участки пути
        public double totalLength;    // общая длина
        public double totalCost;      // общая стоимость

        public Route(String oksId, String chamberId, List<GraphBuilder.Edge> edges) {
            this.oksId = oksId;
            this.chamberId = chamberId;
            this.edges = edges;
            this.totalLength = edges.stream().mapToDouble(e -> e.length).sum();
            this.totalCost = edges.stream().mapToDouble(e -> e.cost).sum();
        }
    }

    /**
     * Строит маршруты для всех точек ОКС.
     *
     * @param oksPoints    точки подключения ОКС
     * @param chambers     существующие тепловые камеры
     * @param obstacles    все препятствия
     * @param diameter     ДУ новой сети (для проверки)
     * @return список маршрутов
     */
    public List<Route> buildRoutes(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            int diameter
    ) {
        log.info("Начинаем маршрутизацию: {} ОКС, {} камер, {} препятствий",
                oksPoints.size(), chambers.size(), obstacles.size());

        // Собираем все узлы (ОКС + камеры)
        List<GeoObject> allNodes = new ArrayList<>();
        allNodes.addAll(oksPoints);
        allNodes.addAll(chambers);

        // Строим граф
        List<GraphBuilder.Edge> edges = graphBuilder.buildGraph(allNodes, obstacles, diameter);

        if (edges.isEmpty()) {
            log.warn("Граф пуст! Возможно, все пути заблокированы препятствиями");
            return Collections.emptyList();
        }

        // Строим список смежности для алгоритма Дейкстры
        Map<String, List<GraphBuilder.Edge>> adjacency = buildAdjacency(edges);

        // Для каждой точки ОКС ищем путь до ближайшей камеры
        List<Route> routes = new ArrayList<>();
        for (GeoObject oks : oksPoints) {
            Route route = findShortestRoute(oks, chambers, adjacency);
            if (route != null) {
                routes.add(route);
                log.info("Маршрут для ОКС {}: {} участков, длина {} м, стоимость {} руб.",
                        oks.getId(), route.edges.size(), route.totalLength, route.totalCost);
            } else {
                log.warn("Маршрут для ОКС {} не найден", oks.getId());
            }
        }

        log.info("Построено маршрутов: {} из {}", routes.size(), oksPoints.size());
        return routes;
    }

    /**
     * Строит список смежности: для каждого узла — список исходящих рёбер.
     */
    private Map<String, List<GraphBuilder.Edge>> buildAdjacency(List<GraphBuilder.Edge> edges) {
        Map<String, List<GraphBuilder.Edge>> adjacency = new HashMap<>();
        for (GraphBuilder.Edge edge : edges) {
            adjacency.computeIfAbsent(edge.fromId, k -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.toId, k -> new ArrayList<>()).add(edge);
        }
        return adjacency;
    }

    /**
     * Алгоритм Дейкстры: ищет кратчайший путь от ОКС до ближайшей камеры.
     */
    private Route findShortestRoute(
            GeoObject oks,
            List<GeoObject> chambers,
            Map<String, List<GraphBuilder.Edge>> adjacency
    ) {
        String startId = oks.getId();

        // Множество ID камер для быстрой проверки
        Set<String> chamberIds = new HashSet<>();
        for (GeoObject chamber : chambers) {
            chamberIds.add(chamber.getId());
        }

        // Расстояния и предшественники
        Map<String, Double> distances = new HashMap<>();
        Map<String, GraphBuilder.Edge> predecessors = new HashMap<>();
        Set<String> visited = new HashSet<>();

        // Очередь с приоритетом (по расстоянию)
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

            // Если дошли до камеры — запоминаем и продолжаем (нужна ближайшая)
            if (chamberIds.contains(current)) {
                closestChamber = current;
                break; // Дейкстра гарантирует, что это ближайшая
            }

            // Просматриваем соседей
            List<GraphBuilder.Edge> neighbors = adjacency.getOrDefault(current, Collections.emptyList());
            for (GraphBuilder.Edge edge : neighbors) {
                String neighbor = edge.fromId.equals(current) ? edge.toId : edge.fromId;

                if (visited.contains(neighbor)) continue;

                double newDistance = distances.get(current) + edge.cost;
                if (newDistance < distances.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    distances.put(neighbor, newDistance);
                    predecessors.put(neighbor, edge);
                    queue.add(neighbor);
                }
            }
        }

        if (closestChamber == null) {
            return null; // Путь не найден
        }

        // Восстанавливаем путь
        List<GraphBuilder.Edge> path = new ArrayList<>();
        String current = closestChamber;
        while (!current.equals(startId)) {
            GraphBuilder.Edge edge = predecessors.get(current);
            if (edge == null) break;
            path.add(0, edge); // добавляем в начало
            current = edge.fromId.equals(current) ? edge.toId : edge.fromId;
        }

        return new Route(startId, closestChamber, path);
    }
}