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

    public int selectDiameterByFlow(double flowTph) {
        for (int i = 0; i < DIAMETERS.length; i++) {
            if (CAPACITY_TPH[i] >= flowTph) return DIAMETERS[i];
        }
        return DIAMETERS[DIAMETERS.length - 1];
    }

    public double maxLengthForDiameter(int diameter) {
        for (int i = 0; i < DIAMETERS.length; i++) {
            if (DIAMETERS[i] == diameter) return MAX_LENGTH_M[i];
        }
        return MAX_LENGTH_M[MAX_LENGTH_M.length - 1];
    }

    public double getCostPerMeter(int diameter) {
        for (int i = 0; i < DIAMETERS.length; i++) {
            if (DIAMETERS[i] == diameter) return COST_PER_M[i];
        }
        return COST_PER_M[COST_PER_M.length - 1];
    }

    public List<CalculatedSegment> calculateMergedSegments(List<RoutingService.Route> routes) {
        log.info("Расчёт объединённых участков для {} маршрутов", routes.size());

        // 1. Группировка рёбер по неориентированному ключу (ТЗ п.2.3).
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
            }
        }

        log.info("Уникальных (неориентированных) рёбер: {}", edgeGroups.size());

        // 2. Начальные ДУ — минимальные по расходу.
        for (MergedEdge me : edgeGroups.values()) {
            me.diameter = selectDiameterByFlow(me.totalFlowTph);
        }

        // 3. Итеративное повышение ДУ по предельной длине участка
        //    с неизменным расходом (разъяснение 1).
        boolean changed = true;
        int iter = 0;
        int maxIter = 50;

        while (changed && iter++ < maxIter) {
            changed = false;

            Map<String, Double> sameFlowPathLengths =
                    computeSameFlowPathLengths(edgeGroups, routes);

            // Для каждого ребра: если длина под-пути с тем же расходом
            // превышает предельную для текущего ДУ — поднимаем ДУ на всём
            // этом под-пути (все рёбра с тем же расходом).
            Set<Double> flowsToRaise = new HashSet<>();
            for (Map.Entry<String, Double> e : sameFlowPathLengths.entrySet()) {
                MergedEdge me = edgeGroups.get(e.getKey());
                if (me == null) continue;
                double pathLen = e.getValue();
                double limit = maxLengthForDiameter(me.diameter);
                if (pathLen > limit) {
                    flowsToRaise.add(me.totalFlowTph);
                }
            }
            for (Double flow : flowsToRaise) {
                if (raiseDiameterForFlow(flow, edgeGroups, sameFlowPathLengths)) {
                    changed = true;
                }
            }
        }

        if (iter >= maxIter) {
            log.warn("Подбор ДУ по предельной длине не стабилизировался за {} итераций", maxIter);
        }

        // 4. Проверка неуменьшения ДУ по направлению к месту присоединения.
        boolean changedDir = true;
        int iterDir = 0;
        while (changedDir && iterDir++ < maxIter) {
            changedDir = false;

            Map<String, Integer> maxInDiameter = new HashMap<>();
            for (RoutingService.Route route : routes) {
                if (route.oksFlowTph == null) continue;
                List<String[]> oriented = buildOrientedEdges(route);
                for (int i = 0; i < route.edges.size(); i++) {
                    GraphBuilder.Edge edge = route.edges.get(i);
                    String toNode = oriented.get(i)[1];
                    String key = makeUndirectedKey(edge.fromId, edge.toId);
                    MergedEdge me = edgeGroups.get(key);
                    if (me == null) continue;
                    maxInDiameter.merge(toNode, me.diameter, Math::max);
                }
            }

            for (RoutingService.Route route : routes) {
                if (route.oksFlowTph == null) continue;
                List<String[]> oriented = buildOrientedEdges(route);
                for (int i = 0; i < route.edges.size(); i++) {
                    GraphBuilder.Edge edge = route.edges.get(i);
                    String fromNode = oriented.get(i)[0];
                    String key = makeUndirectedKey(edge.fromId, edge.toId);
                    MergedEdge me = edgeGroups.get(key);
                    if (me == null) continue;
                    Integer maxIn = maxInDiameter.get(fromNode);
                    if (maxIn != null && maxIn > me.diameter) {
                        me.diameter = maxIn;
                        changedDir = true;
                    }
                }
            }
        }
        if (iterDir >= maxIter) {
            log.warn("Проверка неуменьшения ДУ не стабилизировалась за {} итераций", maxIter);
        }

        // 5. Результат.
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

    /**
     * Для каждого ребра — максимальная длина непрерывного под-пути
     * с ОДИНАКОВЫМ расходом (разъяснение 1: предельная длина проверяется
     * на участок с неизменным расходом целиком).
     */
    private Map<String, Double> computeSameFlowPathLengths(
            Map<String, MergedEdge> edgeGroups,
            List<RoutingService.Route> routes) {

        Map<String, Double> result = new HashMap<>();

        for (RoutingService.Route route : routes) {
            if (route.oksFlowTph == null) continue;
            List<GraphBuilder.Edge> edges = route.edges;
            int n = edges.size();
            if (n == 0) continue;

            int i = 0;
            while (i < n) {
                String keyI = makeUndirectedKey(edges.get(i).fromId, edges.get(i).toId);
                MergedEdge meI = edgeGroups.get(keyI);
                if (meI == null) { i++; continue; }

                double flowI = meI.totalFlowTph;

                int j = i;
                double segLen = 0;
                while (j < n) {
                    String keyJ = makeUndirectedKey(edges.get(j).fromId, edges.get(j).toId);
                    MergedEdge meJ = edgeGroups.get(keyJ);
                    if (meJ == null) break;
                    if (Math.abs(meJ.totalFlowTph - flowI) > 1e-6) break;
                    segLen += meJ.length;
                    j++;
                }

                for (int k = i; k < j; k++) {
                    String keyK = makeUndirectedKey(edges.get(k).fromId, edges.get(k).toId);
                    result.merge(keyK, segLen, Math::max);
                }

                i = j;
            }
        }
        return result;
    }

    /**
     * Поднимает ДУ всем рёбрам с указанным расходом до минимально
     * подходящего по расходу и длине под-пути.
     *
     * @return true, если ДУ хотя бы одного ребра изменился.
     */
    private boolean raiseDiameterForFlow(
            double flow,
            Map<String, MergedEdge> edgeGroups,
            Map<String, Double> sameFlowPathLengths) {

        double maxPathLen = 0;
        int currentDiameter = 0;
        for (Map.Entry<String, Double> e : sameFlowPathLengths.entrySet()) {
            MergedEdge me = edgeGroups.get(e.getKey());
            if (me == null) continue;
            if (Math.abs(me.totalFlowTph - flow) > 1e-6) continue;
            if (e.getValue() > maxPathLen) maxPathLen = e.getValue();
            if (me.diameter > currentDiameter) currentDiameter = me.diameter;
        }

        int targetD = selectDiameter(flow, maxPathLen);
        if (targetD <= currentDiameter) return false;

        boolean changed = false;
        for (MergedEdge me : edgeGroups.values()) {
            if (Math.abs(me.totalFlowTph - flow) > 1e-6) continue;
            if (targetD > me.diameter) {
                me.diameter = targetD;
                changed = true;
            }
        }
        return changed;
    }

    private List<String[]> buildOrientedEdges(RoutingService.Route route) {
        List<String[]> result = new ArrayList<>();
        String current = route.oksId;
        for (GraphBuilder.Edge edge : route.edges) {
            String other;
            if (edge.fromId.equals(current)) {
                other = edge.toId;
            } else if (edge.toId.equals(current)) {
                other = edge.fromId;
            } else {
                other = edge.toId;
            }
            result.add(new String[]{current, other});
            current = other;
        }
        return result;
    }

    private String makeUndirectedKey(String fromId, String toId) {
        return fromId.compareTo(toId) <= 0
                ? fromId + "|" + toId
                : toId + "|" + fromId;
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
    }
}