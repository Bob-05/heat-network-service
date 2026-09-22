package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.geometry.GraphBuilder;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VariantService {

    private final RoutingService routingService;
    private final FlowCalculationService flowCalculationService;
    private final CostService costService;
    private final GraphBuilder graphBuilder;

    private static final int MAX_DIAMETER = 1400;
    private static final int MAX_ITERATIONS = 18;

    public static class Variant {
        public String variantId;
        public int rank;
        public RoutingService.WeightType weightType;
        public List<RoutingService.Route> routes;
        public List<FlowCalculationService.CalculatedSegment> segments;
        public CostService.CostResult cost;
        public List<GeoObject> allOks;
        public List<GeoObject> chambers;
    }

    /**
     * Формирует до 3 содержательно отличающихся вариантов.
     *
     * @param oksPoints        точки ОКС
     * @param chambers         существующие камеры
     * @param obstacles        пространственные ограничения
     * @param existingNetworks существующие тепловые сети (для присоединения)
     */
    public List<Variant> buildVariants(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            List<GeoObject> existingNetworks
    ) {
        log.info("=== Формирование вариантов ===");

        int currentDiameter = MAX_DIAMETER;
        int iteration = 0;

        List<Variant> variants = new ArrayList<>();

        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.info("--- Итерация {}: ДУ = {} ---", iteration, currentDiameter);

            // Три прогона Дейкстры с разными весами
            Variant vLength = runVariant(oksPoints, chambers, obstacles, existingNetworks,
                    RoutingService.WeightType.LENGTH, "vL", currentDiameter);
            Variant vCost = runVariant(oksPoints, chambers, obstacles, existingNetworks,
                    RoutingService.WeightType.COST, "vC", currentDiameter);
            Variant vScore = runVariant(oksPoints, chambers, obstacles, existingNetworks,
                    RoutingService.WeightType.SCORE, "vS", currentDiameter);

            // Максимальный ДУ среди всех вариантов
            int maxDiameter = 0;
            for (Variant v : List.of(vLength, vCost, vScore)) {
                for (FlowCalculationService.CalculatedSegment seg : v.segments) {
                    if (seg.diameter > maxDiameter) maxDiameter = seg.diameter;
                }
            }

            log.info("Итерация {}: текущий ДУ = {}, max ДУ среди вариантов = {}",
                    iteration, currentDiameter, maxDiameter);

            // Стабилизация
            if (maxDiameter >= currentDiameter) {
                log.info("Стабилизация достигнута на итерации {}: ДУ = {}",
                        iteration, currentDiameter);
                variants.add(vLength);
                variants.add(vCost);
                variants.add(vScore);
                break;
            }

            currentDiameter = maxDiameter;
        }

        // Отсев дубликатов
        List<Variant> uniqueVariants = filterUniqueVariants(variants);

        // Ранжирование по score
        uniqueVariants.sort(Comparator.comparingDouble(v -> v.cost.score));
        for (int i = 0; i < uniqueVariants.size(); i++) {
            Variant v = uniqueVariants.get(i);
            v.variantId = "v" + (i + 1);
            v.rank = i + 1;
            log.info("Вариант {}: rank={}, weight={}, score={}, cost={}, length={}",
                    v.variantId, v.rank, v.weightType,
                    String.format(java.util.Locale.US, "%.4f", v.cost.score),
                    Math.round(v.cost.calculatedCost),
                    Math.round(v.cost.newNetworkLength));
        }

        log.info("=== Итого вариантов: {} ===", uniqueVariants.size());
        return uniqueVariants;
    }

    private Variant runVariant(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            List<GeoObject> existingNetworks,
            RoutingService.WeightType weightType,
            String tempId,
            int diameter
    ) {
        List<RoutingService.Route> routes = routingService.buildRoutes(
                oksPoints, chambers, obstacles, existingNetworks, diameter, weightType);

        // ОБЪЕДИНЁННЫЕ участки (для стоимости)
        List<FlowCalculationService.CalculatedSegment> segments =
                flowCalculationService.calculateMergedSegments(routes);

        // ДУ для каждого маршрута отдельно (для определения ДУ камер)
        Map<String, Integer> oksDiameters = new HashMap<>();
        for (RoutingService.Route route : routes) {
            if (route.oksFlowTph == null) continue;
            int routeDiameter = flowCalculationService.selectDiameter(
                    route.oksFlowTph, route.totalLength);
            oksDiameters.put(route.oksId, routeDiameter);
        }

        List<String> connectedOksIds = new ArrayList<>();
        for (RoutingService.Route route : routes) {
            connectedOksIds.add(route.oksId);
        }

        CostService.CostResult cost = costService.calculate(
                segments, connectedOksIds, oksPoints,
                routes, chambers, existingNetworks, oksDiameters);

        Variant variant = new Variant();
        variant.variantId = tempId;
        variant.weightType = weightType;
        variant.routes = routes;
        variant.segments = segments;
        variant.cost = cost;
        variant.allOks = oksPoints;
        variant.chambers = chambers;

        return variant;
    }

    private List<Variant> filterUniqueVariants(List<Variant> variants) {
        List<Variant> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Variant v : variants) {
            String signature = buildSignature(v);
            if (seen.contains(signature)) {
                log.info("Вариант {} отсеян как дубликат", v.variantId);
                continue;
            }
            seen.add(signature);
            unique.add(v);
        }
        return unique;
    }

    /**
     * Подпись варианта: набор пар (oksId → endNodeId|isChamber).
     * Два варианта одинаковы, если все ОКС ведут к одинаковым узлам.
     */
    private String buildSignature(Variant v) {
        List<String> pairs = new ArrayList<>();
        for (RoutingService.Route route : v.routes) {
            pairs.add(route.oksId + "→" + route.endNodeId + "|" + route.endIsChamber);
        }
        pairs.sort(String::compareTo);
        return String.join(";", pairs);
    }
}