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

    public static class CalculatedSegment {
        public String oksId;
        public String routeEndNodeId;
        public boolean routeEndIsChamber;
        public String segmentStartNodeId;
        public String segmentEndNodeId;
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
     * Группирует рёбра маршрутов по неориентированному ключу (дедупликация общих
     * участков), выбирает ДУ по суммарному расходу ребра и максимальной длине
     * пути, обеспечивает неуменьшение ДУ к месту присоединения (ТЗ п.2.3).
     *
     * ВАЖНО: дедупликация общих участков происходит именно здесь — через
     * makeUndirectedKey. Сворачивание последовательных рёбер в один LineString
     * (для представления в GeoJSON) выполняется в GeoJsonWriterService и НЕ
     * должно попадать сюда: свернуть рёбра до группировки = потерять
     * дедупликацию и завысить суммарную длину.
     */
    public List<CalculatedSegment> calculateMergedSegments(List<RoutingService.Route> routes) {
        log.info("Расчёт объединённых участков для {} маршрутов", routes.size());

        // 1. Группировка рёбер по неориентированному ключу
        Map<String, MergedEdge> edgeGroups = new LinkedHashMap<>();

        for (RoutingService.Route route : routes) {
            if (route.oksFlowTph == null) continue;

            for (GraphBuilder.Edge edge : route.edges) {
                String key = makeUndirectedKey(edge.fromId, edge.toId);

                MergedEdge me = edgeGroups.computeIfAbsent(key, k -> new MergedEdge());
                if (me.fromId == null) {
                    me.fromId = edge.fromId;
                    me.toId = edge.toId;
                    me.geometry = edge.geometry;
                    me.length = edge.length;
                    me.layingMethod = edge.layingMethod;
                    me.kspec = edge.kspec;
                }

                if (!me.oksIds.contains(route.oksId)) {
                    me.oksIds.add(route.oksId);
                    me.totalFlowTph += route.oksFlowTph;
                }

                // Предельная длина проверяется по непрерывному пути (ТЗ п.2.3):
                // берём максимум длины среди маршрутов, проходящих через ребро.
                if (route.totalLength > me.maxPathLength) {
                    me.maxPathLength = route.totalLength;
                }
            }
        }

        log.info("Уникальных (неориентированных) рёбер: {}", edgeGroups.size());

        // 2. Выбор ДУ по суммарному расходу и максимальной длине пути
        for (MergedEdge me : edgeGroups.values()) {
            me.diameter = selectDiameter(me.totalFlowTph, me.maxPathLength);
            log.debug("Ребро {}→{}: расход {} т/ч, макс.длина пути {} м → ДУ {}",
                    me.fromId, me.toId,
                    Math.round(me.totalFlowTph * 100.0) / 100.0,
                    Math.round(me.maxPathLength),
                    me.diameter);
        }

        // 3. Проверка неуменьшения ДУ по направлению к сети (ТЗ п.2.3)
        boolean changed = true;
        int iterations = 0;
        int maxIterations = 50;

        while (changed && iterations++ < maxIterations) {
            changed = false;

            Map<String, Integer> maxInDiameter = new HashMap<>();
            for (RoutingService.Route route : routes) {
                if (route.oksFlowTph == null) continue;
                for (GraphBuilder.Edge edge : route.edges) {
                    String key = makeUndirectedKey(edge.fromId, edge.toId);
                    MergedEdge me = edgeGroups.get(key);
                    if (me == null) continue;
                    int cur = maxInDiameter.getOrDefault(edge.toId, 0);
                    if (me.diameter > cur) {
                        maxInDiameter.put(edge.toId, me.diameter);
                    }
                }
            }

            for (RoutingService.Route route : routes) {
                if (route.oksFlowTph == null) continue;
                for (GraphBuilder.Edge edge : route.edges) {
                    String key = makeUndirectedKey(edge.fromId, edge.toId);
                    MergedEdge me = edgeGroups.get(key);
                    if (me == null) continue;

                    Integer maxIn = maxInDiameter.get(edge.fromId);
                    if (maxIn != null && maxIn > me.diameter) {
                        me.diameter = maxIn;
                        changed = true;
                    }
                }
            }
        }

        if (iterations >= maxIterations) {
            log.warn("Проверка неуменьшения ДУ не стабилизировалась за {} итераций", maxIterations);
        }

        // 4. Формирование результата
        List<CalculatedSegment> result = new ArrayList<>();

        for (MergedEdge me : edgeGroups.values()) {
            CalculatedSegment seg = new CalculatedSegment();
            seg.oksId = String.join(",", me.oksIds);
            seg.segmentStartNodeId = me.fromId;
            seg.segmentEndNodeId = me.toId;
            seg.geometry = me.geometry;
            seg.length = me.length;
            seg.flowTph = me.totalFlowTph;
            seg.diameter = me.diameter > 0 ? me.diameter : 50;
            seg.layingMethod = me.layingMethod;
            seg.kspec = me.kspec;

            double costPerMeter = getCostPerMeter(seg.diameter);
            seg.cost = me.length * costPerMeter * me.kspec;

            result.add(seg);

            log.info("Участок {}→{}: расход {} т/ч, ДУ={}, длина {} м",
                    me.fromId, me.toId,
                    Math.round(me.totalFlowTph * 100.0) / 100.0,
                    seg.diameter,
                    Math.round(me.length));
        }

        log.info("Объединённых участков: {}", result.size());
        return result;
    }

    private String makeUndirectedKey(String fromId, String toId) {
        return fromId.compareTo(toId) <= 0
                ? fromId + "|" + toId
                : toId + "|" + fromId;
    }

    public List<CalculatedSegment> calculateSegments(List<RoutingService.Route> routes) {
        log.info("Начинаем расчёт расходов и ДУ для {} маршрутов", routes.size());
        List<CalculatedSegment> result = new ArrayList<>();

        for (RoutingService.Route route : routes) {
            if (route.oksFlowTph == null) continue;
            double oksFlow = route.oksFlowTph;
            double totalLength = route.totalLength;
            int diameter = selectDiameter(oksFlow, totalLength);

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
        return result;
    }

    private static class MergedEdge {
        String fromId;
        String toId;
        LineString geometry;
        double length;
        String layingMethod;
        double kspec;
        Set<String> oksIds = new HashSet<>();
        double totalFlowTph = 0;
        int diameter = 0;
        double maxPathLength = 0;
    }
}