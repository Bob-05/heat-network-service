package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.geometry.GraphBuilder;

import java.util.ArrayList;
import java.util.List;

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
        public String oksId;                // ID ОКС, к которой относится участок
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
}