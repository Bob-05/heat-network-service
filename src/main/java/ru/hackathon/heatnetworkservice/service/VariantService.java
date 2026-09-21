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

/**
 * Сервис формирования вариантов подключения.
 *
 * По ТЗ (раздел 6):
 * - В каждом режиме сервис может вернуть до трёх содержательно отличающихся вариантов.
 * - Итоговый показатель S = 0,7 · (C / 25 000 000) + 0,3 · (L / 100).
 * - Чем меньше S, тем выше вариант.
 *
 * Алгоритм:
 * 1. Строим граф с максимальным ДУ (1400 мм) — это даёт максимальный отступ oks (9 м).
 * 2. Для каждого типа веса (LENGTH, COST, SCORE) ищем маршруты и считаем ДУ.
 * 3. Определяем максимальный ДУ среди всех вариантов.
 * 4. Если max ДУ < текущего — перестраиваем граф с новым ДУ и повторяем.
 * 5. Стабилизация: max ДУ не изменился.
 * 6. Ранжируем варианты по score.
 * 7. Отсеиваем содержательно одинаковые варианты (по набору маршрутов).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VariantService {

    private final RoutingService routingService;
    private final FlowCalculationService flowCalculationService;
    private final CostService costService;
    private final GraphBuilder graphBuilder;

    /** Максимальный ДУ из Таблицы 1 — используется как стартовый. */
    private static final int MAX_DIAMETER = 1400;

    /** Максимальное количество итераций (защита от бесконечного цикла). */
    private static final int MAX_ITERATIONS = 18;

    /**
     * Один вариант подключения.
     */
    public static class Variant {
        public String variantId;                    // "v1", "v2", "v3"
        public int rank;                            // 1, 2, 3
        public RoutingService.WeightType weightType;
        public List<RoutingService.Route> routes;
        public List<FlowCalculationService.CalculatedSegment> segments;
        public CostService.CostResult cost;
        public List<GeoObject> allOks;
        public List<GeoObject> chambers;
    }

    /**
     * Формирует до 3 содержательно отличающихся вариантов.
     */
    public List<Variant> buildVariants(
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            List<GeoObject> obstacles
    ) {
        log.info("=== Формирование вариантов ===");

        // Собираем все узлы графа (ОКС + камеры)
        List<GeoObject> allNodes = new ArrayList<>();
        allNodes.addAll(oksPoints);
        allNodes.addAll(chambers);

        int currentDiameter = MAX_DIAMETER;
        int iteration = 0;

        List<Variant> variants = new ArrayList<>();

        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.info("--- Итерация {}: ДУ = {} ---", iteration, currentDiameter);

            // Строим граф с текущим ДУ
            List<GraphBuilder.Edge> edges = graphBuilder.buildGraph(
                    allNodes, obstacles, currentDiameter);

            if (edges.isEmpty()) {
                log.warn("Граф пуст на итерации {} — прекращаем", iteration);
                break;
            }

            // Три прогона Дейкстры с разными весами
            Variant vLength = runVariant(edges, oksPoints, chambers,
                    RoutingService.WeightType.LENGTH, "vL");
            Variant vCost = runVariant(edges, oksPoints, chambers,
                    RoutingService.WeightType.COST, "vC");
            Variant vScore = runVariant(edges, oksPoints, chambers,
                    RoutingService.WeightType.SCORE, "vS");

            // Определяем максимальный ДУ среди всех вариантов
            int maxDiameter = 0;
            for (Variant v : List.of(vLength, vCost, vScore)) {
                for (FlowCalculationService.CalculatedSegment seg : v.segments) {
                    if (seg.diameter > maxDiameter) {
                        maxDiameter = seg.diameter;
                    }
                }
            }

            log.info("Итерация {}: текущий ДУ = {}, max ДУ среди вариантов = {}",
                    iteration, currentDiameter, maxDiameter);

            // Проверяем стабилизацию
            if (maxDiameter >= currentDiameter) {
                log.info("Стабилизация достигнута на итерации {}: ДУ = {}",
                        iteration, currentDiameter);

                // Сохраняем варианты этой итерации
                variants.add(vLength);
                variants.add(vCost);
                variants.add(vScore);
                break;
            }

            // Иначе — перестраиваем граф с меньшим ДУ
            currentDiameter = maxDiameter;
        }

        // Отсеиваем содержательно одинаковые варианты
        List<Variant> uniqueVariants = filterUniqueVariants(variants);

        // Присваиваем ID и ранжируем по score
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

    /**
     * Запускает один вариант: маршруты + расход + стоимость.
     */
    private Variant runVariant(
            List<GraphBuilder.Edge> edges,
            List<GeoObject> oksPoints,
            List<GeoObject> chambers,
            RoutingService.WeightType weightType,
            String tempId
    ) {
        // Маршруты
        List<RoutingService.Route> routes = routingService.findRoutes(
                edges, oksPoints, chambers, weightType);

        // Расчёт расходов и ДУ
        List<FlowCalculationService.CalculatedSegment> segments =
                flowCalculationService.calculateSegments(routes);

        // Собираем ID подключённых ОКС
        List<String> connectedOksIds = new ArrayList<>();
        for (RoutingService.Route route : routes) {
            connectedOksIds.add(route.oksId);
        }

        // Стоимость
        CostService.CostResult cost = costService.calculate(
                segments, connectedOksIds, oksPoints);

        // Формируем вариант
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

    /**
     * Отсеивает содержательно одинаковые варианты.
     *
     * Сравниваем по набору (oksId → chamberId) — если у двух вариантов
     * все ОКС подключены к тем же камерам, они одинаковые.
     */
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
     * Строит подпись варианта: множество пар (oksId → chamberId).
     */
    private String buildSignature(Variant v) {
        List<String> pairs = new ArrayList<>();
        for (RoutingService.Route route : v.routes) {
            pairs.add(route.oksId + "→" + route.chamberId);
        }
        pairs.sort(String::compareTo);
        return String.join("|", pairs);
    }
}