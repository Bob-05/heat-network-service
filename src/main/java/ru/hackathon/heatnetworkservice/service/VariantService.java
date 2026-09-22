package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.geometry.GraphBuilder;
import ru.hackathon.heatnetworkservice.geometry.RoutingConfig;
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
    private final GraphBuilder graphBuilder;
    private final RoutingConfig routingConfig;

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
        /** Итоговый радиус, при котором получен вариант (null = без ограничения). */
        public Double finalRadius;
    }

    /**
     * Формирует до 3 содержательно отличающихся вариантов.
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
            log.info("--- Итерация ДУ {}: ДУ = {} ---", iteration, currentDiameter);

            Variant vLength = runVariant(oksPoints, chambers, obstacles, existingNetworks,
                    RoutingService.WeightType.LENGTH, "vL", currentDiameter);
            Variant vCost = runVariant(oksPoints, chambers, obstacles, existingNetworks,
                    RoutingService.WeightType.COST, "vC", currentDiameter);
            Variant vScore = runVariant(oksPoints, chambers, obstacles, existingNetworks,
                    RoutingService.WeightType.SCORE, "vS", currentDiameter);

            int maxDiameter = 0;
            for (Variant v : List.of(vLength, vCost, vScore)) {
                for (FlowCalculationService.CalculatedSegment seg : v.segments) {
                    if (seg.diameter > maxDiameter) maxDiameter = seg.diameter;
                }
            }

            log.info("Итерация ДУ {}: текущий ДУ = {}, max ДУ среди вариантов = {}",
                    iteration, currentDiameter, maxDiameter);

            if (maxDiameter >= currentDiameter) {
                log.info("Стабилизация ДУ достигнута на итерации {}: ДУ = {}",
                        iteration, currentDiameter);
                variants.add(vLength);
                variants.add(vCost);
                variants.add(vScore);
                break;
            }

            currentDiameter = maxDiameter;
        }

        List<Variant> uniqueVariants = filterUniqueVariants(variants);

        uniqueVariants.sort(Comparator.comparingDouble(v -> v.cost.score));
        for (int i = 0; i < uniqueVariants.size(); i++) {
            Variant v = uniqueVariants.get(i);
            v.variantId = "v" + (i + 1);
            v.rank = i + 1;
            log.info("Вариант {}: rank={}, weight={}, score={}, cost={}, length={}, radius={}",
                    v.variantId, v.rank, v.weightType,
                    String.format(java.util.Locale.US, "%.4f", v.cost.score),
                    Math.round(v.cost.calculatedCost),
                    Math.round(v.cost.newNetworkLength),
                    v.finalRadius == null ? "без ограничения" : Math.round(v.finalRadius) + " м");
        }

        log.info("=== Итого вариантов: {} ===", uniqueVariants.size());
        return uniqueVariants;
    }

    /**
     * Запускает один вариант: итеративное расширение радиуса → маршруты → ДУ → стоимость.
     */
    private Variant runVariant(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles,
            List<GeoObject> existingNetworks,
            RoutingService.WeightType weightType,
            String tempId,
            int diameter
    ) {
        double radius = routingConfig.getNetworkRadiusStart();
        boolean unlimited = false;
        int noProgressCount = 0;
        int connectedPrev = -1;

        List<RoutingService.Route> routes = null;
        Double finalRadius = null;

        for (int attempt = 0; attempt < routingConfig.getMaxRadiusIterations(); attempt++) {
            Double currentRadius = unlimited ? null : radius;

            log.info("Итерация радиуса {} для веса {}: радиус = {}",
                    attempt + 1, weightType,
                    unlimited ? "без ограничения" : Math.round(radius) + " м");

            routes = routingService.buildRoutes(
                    oksPoints, chambers, obstacles, existingNetworks,
                    diameter, currentRadius, weightType);

            int connectedNow = countConnected(routes);
            log.info("Подключено ОКС: {} из {} (радиус {})",
                    connectedNow, oksPoints.size(),
                    unlimited ? "∞" : Math.round(radius));

            finalRadius = currentRadius;

            // Все подключены — стоп
            if (connectedNow == oksPoints.size()) {
                log.info("Все ОКС подключены на итерации радиуса {}", attempt + 1);
                break;
            }

            // Прогресс
            if (connectedNow <= connectedPrev) {
                noProgressCount++;
                log.info("Прогресса нет ({} подряд)", noProgressCount);
                if (noProgressCount >= routingConfig.getNoProgressIterationsToStop()) {
                    log.warn("Останов: нет прогресса {} итераций подряд", noProgressCount);
                    break;
                }
            } else {
                noProgressCount = 0;
            }
            connectedPrev = connectedNow;

            // Если 0 подключено — переход к без ограничения
            if (connectedNow == 0 && !unlimited) {
                log.warn("Ни один ОКС не подключён. Переход к поиску без ограничения радиуса.");
                unlimited = true;
                continue;
            }

            // Если уже без ограничения и всё равно нет прогресса — стоп
            if (unlimited) {
                log.warn("Без ограничения радиуса прогресса нет. Останов.");
                break;
            }

            // Расширяем радиус
            radius *= routingConfig.getNetworkRadiusMultiplier();
        }

        if (routes == null) {
            routes = new ArrayList<>();
        }

        // Объединённые участки
        List<FlowCalculationService.CalculatedSegment> segments =
                flowCalculationService.calculateMergedSegments(routes);

        // ДУ для каждого маршрута
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

        // Логируем неподключённые ОКС
        for (GeoObject oks : oksPoints) {
            if (!connectedOksIds.contains(oks.getId())) {
                log.warn("ОКС {} не подключена. Причина: не найден допустимый маршрут " +
                                "при радиусе {} (расход {} т/ч)",
                        oks.getId(),
                        finalRadius == null ? "∞" : Math.round(finalRadius) + " м",
                        oks.getFlowTph());
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
        variant.finalRadius = finalRadius;

        return variant;
    }

    private int countConnected(List<RoutingService.Route> routes) {
        if (routes == null) return 0;
        return routes.size();
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