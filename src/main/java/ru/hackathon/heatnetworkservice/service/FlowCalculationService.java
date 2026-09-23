package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.geometry.GraphBuilder;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowCalculationService {

    private static final int[] DIAMETERS = {50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400};
    private static final double[] CAPACITY_TPH = {3.5, 8.3, 13.2, 22.3, 40.2, 65.1, 152.3, 274.9, 437.4, 943.1, 1663.4, 2627.7, 3735.1, 5296.8, 7165.0, 9391.8, 15012.8, 22501.9};
    private static final double[] MAX_LENGTH_M = {181, 245, 327, 419, 554, 696, 1042, 1379, 1718, 2477, 3245, 4037, 4775, 5644, 6518, 7419, 9288, 11276};
    private static final double[] COST_PER_M = {74023, 78631, 83530, 89748, 97275, 105507, 120275, 135323, 150022, 190299, 224137, 264790, 324298, 325996, 327693, 418777, 428074, 683417};

    /**
     * Рассчитанный участок.
     */
    public static class CalculatedSegment {
        public String oksId;                // ID ОКС (или список через запятую для общих участков)
        public String routeEndNodeId;       // ID конечного узла маршрута (камера или точка сети)
        public boolean routeEndIsChamber;   // true — камера, false — точка сети
        public String segmentStartNodeId;   // начало конкретного участка
        public String segmentEndNodeId;     // конец конкретного участка
        public LineString geometry;
        public double length;
        public double flowTph;
        public int diameter;
        public String layingMethod;
        public double kspec;
        public double cost;
    }

    public int selectDiameter(double flowTph, double pathLength) {
        for (int i = 0; i < DIAMETERS.length; i++) {
            if (CAPACITY_TPH[i] >= flowTph && MAX_LENGTH_M[i] >= pathLength) {
                return DIAMETERS[i];
            }
        }
        log.warn("Ни один ДУ не подошёл для расхода {} т/ч и длины {} м", flowTph, pathLength);
        return DIAMETERS[DIAMETERS.length - 1];
    }

    public double getCostPerMeter(int diameter) {
        for (int i = 0; i < DIAMETERS.length; i++) {
            if (DIAMETERS[i] == diameter) return COST_PER_M[i];
        }
        return COST_PER_M[COST_PER_M.length - 1];
    }

    /**
     * Старый метод — рассчитывает ДУ для каждого маршрута отдельно.
     * Оставлен для совместимости.
     */
    public List<CalculatedSegment> calculateSegments(List<RoutingService.Route> routes) {
        log.info("Начинаем расчёт расходов и ДУ для {} маршрутов", routes.size());

        List<CalculatedSegment> result = new ArrayList<>();

        for (RoutingService.Route route : routes) {
            if (route.oksFlowTph == null) {
                log.warn("У маршрута ОКС {} отсутствует расход — пропускаем", route.oksId);
                continue;
            }

            double oksFlow = route.oksFlowTph;
            double totalLength = route.totalLength;

            int diameter = selectDiameter(oksFlow, totalLength);

            log.info("ОКС {}: расход {} т/ч, длина {} м → ДУ {} (конец: {})",
                    route.oksId, oksFlow, Math.round(totalLength), diameter,
                    route.endIsChamber ? "камера " + route.endNodeId : "сеть " + route.endNodeId);

            for (GraphBuilder.Edge edge : route.edges) {
                CalculatedSegment seg = new CalculatedSegment();
                seg.oksId = route.oksId;
                seg.routeEndNodeId = route.endNodeId;
                seg.routeEndIsChamber = route.endIsChamber;
                seg.segmentStartNodeId = edge.fromId;
                seg.segmentEndNodeId = edge.toId;
                seg.geometry = edge.geometry;
                seg.length = edge.length;
                seg.flowTph = oksFlow;
                seg.diameter = diameter;
                seg.layingMethod = edge.layingMethod;
                seg.kspec = edge.kspec;

                double costPerMeter = getCostPerMeter(diameter);
                seg.cost = edge.length * costPerMeter * edge.kspec;

                result.add(seg);
            }
        }

        log.info("Рассчитано участков: {}", result.size());
        return result;
    }

    /**
     * Рассчитывает ДУ для ОБЪЕДИНЁННЫХ участков.
     *
     * Логика (подход B):
     * 1. Для каждого маршрута суммируем длины ВСЕХ его рёбер → totalPathLength.
     * 2. Для каждого уникального ребра берём МАКСИМАЛЬНУЮ суммарную длину
     *    среди маршрутов, через него проходящих.
     * 3. Подбираем ДУ по суммарному расходу и этой максимальной длине.
     * 4. Все рёбра одного маршрута получают один ДУ.
     *
     * Это гарантирует соблюдение предельной длины по каждому непрерывному пути
     * (разъяснение 2 ТЗ).
     */
    public List<CalculatedSegment> calculateMergedSegments(List<RoutingService.Route> routes) {
        log.info("Расчёт объединённых участков для {} маршрутов", routes.size());

        // 1. Считаем суммарную длину каждого маршрута
        Map<String, Double> oksTotalLength = new HashMap<>();
        for (RoutingService.Route route : routes) {
            if (route.oksFlowTph == null) continue;
            oksTotalLength.put(route.oksId, route.totalLength);
        }

        // 2. Группируем рёбра по паре (fromId, toId)
        Map<String, EdgeInfo> edgeGroups = new LinkedHashMap<>();

        for (RoutingService.Route route : routes) {
            if (route.oksFlowTph == null) continue;

            double routeLength = route.totalLength;

            for (GraphBuilder.Edge edge : route.edges) {
                String key = makeEdgeKey(edge.fromId, edge.toId);

                EdgeInfo info = edgeGroups.computeIfAbsent(key, k -> new EdgeInfo());
                info.fromId = edge.fromId;
                info.toId = edge.toId;
                info.geometry = edge.geometry;
                info.length = edge.length;
                info.layingMethod = edge.layingMethod;
                info.kspec = edge.kspec;

                // Добавляем ОКС, если ещё не добавлена
                if (!info.oksIds.contains(route.oksId)) {
                    info.oksIds.add(route.oksId);
                    info.totalFlowTph += route.oksFlowTph;
                }

                // Максимальная суммарная длина пути среди ОКС, идущих через это ребро
                if (routeLength > info.maxPathLength) {
                    info.maxPathLength = routeLength;
                }
            }
        }

        log.info("Уникальных рёбер: {}", edgeGroups.size());

        // 3. Для каждого уникального ребра подбираем ДУ и считаем стоимость
        List<CalculatedSegment> result = new ArrayList<>();

        for (EdgeInfo info : edgeGroups.values()) {
            // ДУ по суммарному расходу И максимальной длине пути
            int diameter = selectDiameter(info.totalFlowTph, info.maxPathLength);

            CalculatedSegment seg = new CalculatedSegment();
            seg.oksId = String.join(",", info.oksIds);
            seg.segmentStartNodeId = info.fromId;
            seg.segmentEndNodeId = info.toId;
            seg.geometry = info.geometry;
            seg.length = info.length;
            seg.flowTph = info.totalFlowTph;
            seg.diameter = diameter;
            seg.layingMethod = info.layingMethod;
            seg.kspec = info.kspec;

            double costPerMeter = getCostPerMeter(diameter);
            seg.cost = info.length * costPerMeter * info.kspec;

            result.add(seg);

            log.info("Ребро {}→{}: расход {} т/ч ({} ОКС), макс.длина пути {} м, ДУ={}, длина ребра {} м",
                    info.fromId, info.toId,
                    Math.round(info.totalFlowTph * 100.0) / 100.0,
                    info.oksIds.size(),
                    Math.round(info.maxPathLength),
                    diameter,
                    Math.round(info.length));
        }

        log.info("Объединённых участков: {}", result.size());
        return result;
    }

    /**
     * Создаёт ключ для ребра (не зависит от направления).
     */
    private String makeEdgeKey(String fromId, String toId) {
        return fromId.compareTo(toId) <= 0
                ? fromId + "→" + toId
                : toId + "→" + fromId;
    }

    /**
     * Внутренний класс для группировки рёбер.
     */
    private static class EdgeInfo {
        String fromId;
        String toId;
        LineString geometry;
        double length;
        String layingMethod;
        double kspec;
        Set<String> oksIds = new HashSet<>();
        double totalFlowTph = 0;
        /** Максимальная суммарная длина пути среди ОКС, через это ребро проходящих. */
        double maxPathLength = 0;
    }
}