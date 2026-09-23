package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class VariantService {

    private final RoutingService routingService;
    private final FlowCalculationService flowCalculationService;
    private final CostService costService;

    /** Минимальный ДУ для построения графа. */
    private static final int MIN_DIAMETER = 50;

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
     * Одна итерация — без расширения радиуса и без итераций по ДУ.
     */
    public List<Variant> buildVariants(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            List<GeoObject> existingNetworks
    ) {
        log.info("=== Формирование вариантов ===");

        List<Variant> variants = new ArrayList<>();

        Variant vLength = runVariant(oksPoints, chambers, obstacles, existingNetworks,
                RoutingService.WeightType.LENGTH, "vL");
        Variant vCost = runVariant(oksPoints, chambers, obstacles, existingNetworks,
                RoutingService.WeightType.COST, "vC");
        Variant vScore = runVariant(oksPoints, chambers, obstacles, existingNetworks,
                RoutingService.WeightType.SCORE, "vS");

        variants.add(vLength);
        variants.add(vCost);
        variants.add(vScore);

        List<Variant> uniqueVariants = filterUniqueVariants(variants);

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
            String tempId
    ) {
        // ОДИН вызов без радиуса и без итераций по ДУ
        List<RoutingService.Route> routes = routingService.buildRoutes(
                oksPoints, chambers, obstacles, existingNetworks, MIN_DIAMETER, weightType);

        List<FlowCalculationService.CalculatedSegment> segments =
                flowCalculationService.calculateMergedSegments(routes);

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

        for (GeoObject oks : oksPoints) {
            if (!connectedOksIds.contains(oks.getId())) {
                log.warn("ОКС {} не подключена. Причина: не найден допустимый маршрут (расход {} т/ч)",
                        oks.getId(), oks.getFlowTph());
            }
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

    private String buildSignature(Variant v) {
        List<String> pairs = new ArrayList<>();
        for (RoutingService.Route route : v.routes) {
            pairs.add(route.oksId + "→" + route.endNodeId + "|" + route.endIsChamber);
        }
        pairs.sort(String::compareTo);
        return String.join(";", pairs);
    }
}