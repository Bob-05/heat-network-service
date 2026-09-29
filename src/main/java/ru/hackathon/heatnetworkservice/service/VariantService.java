package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.geometry.GraphBuilder;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VariantService {

    private final RoutingService routingService;
    private final FlowCalculationService flowCalculationService;
    private final CostService costService;

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

    public List<Variant> buildVariants(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            List<GeoObject> existingNetworks
    ) {
        log.info("=== Формирование вариантов ===");

        List<GeoObject> oksRestrictions = obstacles.stream()
                .filter(o -> "oks".equals(o.getRestrictionType()))
                .collect(Collectors.toList());

        List<GeoObject> otherObstacles = obstacles.stream()
                .filter(o -> !"oks".equals(o.getRestrictionType()))
                .collect(Collectors.toList());

        List<GeoObject> allObstacles = new ArrayList<>(otherObstacles);
        allObstacles.addAll(oksRestrictions);

        long t0 = System.currentTimeMillis();
        List<GraphBuilder.Edge> edges = routingService.buildGraphOnce(
                oksPoints, chambers, allObstacles, oksRestrictions,
                existingNetworks, MIN_DIAMETER);
        log.info("Граф построен один раз: {} рёбер за {} мс",
                edges.size(), System.currentTimeMillis() - t0);

        List<Variant> variants = new ArrayList<>();
        variants.add(runVariant(edges, oksPoints, chambers, existingNetworks,
                RoutingService.WeightType.LENGTH, "vL"));
        variants.add(runVariant(edges, oksPoints, chambers, existingNetworks,
                RoutingService.WeightType.COST, "vC"));
        variants.add(runVariant(edges, oksPoints, chambers, existingNetworks,
                RoutingService.WeightType.SCORE, "vS"));

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

        return uniqueVariants;
    }

    private Variant runVariant(
            List<GraphBuilder.Edge> edges,
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> existingNetworks,
            RoutingService.WeightType weightType,
            String tempId
    ) {
        List<RoutingService.Route> routes = routingService.findRoutes(
                edges, oksPoints, chambers, weightType);

        List<FlowCalculationService.CalculatedSegment> segments =
                flowCalculationService.calculateMergedSegments(routes);

        Map<String, Integer> oksDiameters = new HashMap<>();
        for (FlowCalculationService.CalculatedSegment seg : segments) {
            if (seg.oksId == null) continue;
            String[] singleOksIds = seg.oksId.split(",");
            for (String singleId : singleOksIds) {
                String trimmed = singleId.trim();
                if (trimmed.isEmpty()) continue;
                int currentMax = oksDiameters.getOrDefault(trimmed, 0);
                if (seg.diameter > currentMax) {
                    oksDiameters.put(trimmed, seg.diameter);
                }
            }
        }

        List<String> connectedOksIds = routes.stream()
                .map(r -> r.oksId)
                .collect(Collectors.toList());

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
            for (var edge : route.edges) {
                pairs.add(edge.fromId + "→" + edge.toId);
            }
        }
        pairs.sort(String::compareTo);
        return String.join(";", pairs);
    }
}