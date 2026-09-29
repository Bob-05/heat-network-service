package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.geometry.CoordinateTransformer;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сервис определения типа присоединения и подсчёта врезок.
 *
 * Правила (ТЗ п.2.1, п.2.4 + разъяснения 11, 12):
 *
 * 1. ТЗ п.2.4: если точка присоединения не далее 10 м от существующей
 *    heat_chamber и после подключения к камере будет примыкать не
 *    более 4 линейных участков — используется эта камера.
 *    Стоимость одной врезки 5 000 000 руб.
 *
 * 2. ТЗ п.2.1: «Разветвления выполняются только в тепловых камерах.»
 *    Поэтому ЛЮБОЙ узел графа со степенью >= 3, который не является
 *    ОКС-точкой и не является существующей/уже созданной камерой,
 *    автоматически повышается до новой heat_chamber (junction chamber).
 *    Диаметр = максимум ДУ примыкающих участков, стоимость — по таблице 3.2.
 *
 * 3. ТЗ п.2.4: новая камера в точке присоединения, если условия п.1
 *    не выполняются. Диаметр по наибольшему ДУ примыкающих участков.
 *    Стоимость новой камеры включает присоединение к существующей сети.
 *
 * 4. ВАЖНО: подсчёт примыканий учитывает как существующие, так и новые,
 *    созданные в рамках текущего построения (в т.ч. junction chambers).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TieInService {

    /** ТЗ п.2.4: радиус, в котором endpoint считается «у существующей камеры». */
    private static final double MAX_DISTANCE_TO_CHAMBER_M = 10.0;

    /** Разъяснение 12: не более 4 примыкающих линейных участков. */
    private static final int MAX_TIE_INS = 4;

    /** Допуск на совпадение вершины сети с камерой (м). */
    private static final double VERTEX_MATCH_TOLERANCE_M = 1.0;

    /** Стоимость одной врезки в существующую камеру (руб.). */
    private static final double TIE_IN_COST = 5_000_000.0;

    /** Округление координат при объединении новых камер (м). */
    private static final double MERGE_PRECISION_M = 1.0;

    /** ТЗ п.2.1: разветвление только в тепловой камере. */
    private static final int JUNCTION_MIN_DEGREE = 3;

    private final CoordinateTransformer coordinateTransformer;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    private int chamberIdCounter = 0;
    private int junctionIdCounter = 0;

    public static class TieInResult {
        public String oksId;
        public String endNodeId;
        public boolean useExistingChamber;
        public String existingChamberId;
        public String newChamberId;
        public Coordinate newChamberCoordinate;
        public int newChamberDiameter;
        public double newChamberCost;
        public double tieInCost;
        public int existingChamberTieInCount;
        /** Флаг для отладки: создан ли этот TieInResult как junction-камера. */
        public boolean isJunction;
    }

    /**
     * Основной метод.
     *
     * @param routes              маршруты, построенные RoutingService
     * @param segments            дедуплицированные сегменты (может мутироваться:
     *                            мы переименовываем tn_* узлы в ID junction-камер)
     * @param chambers            список существующих камер (GeoObject)
     * @param existingNetworks    существующие сети (для подсчёта примыканий)
     * @param oksDiameters        ДУ для каждой ОКС (по наибольшему участку её маршрута)
     */
    public List<TieInResult> determineTieIns(
            List<RoutingService.Route> routes,
            List<FlowCalculationService.CalculatedSegment> segments,
            List<GeoObject> chambers,
            List<GeoObject> existingNetworks,
            Map<String, Integer> oksDiameters
    ) {
        log.info("Определение типа присоединения для {} маршрутов, {} сегментов",
                routes.size(), segments == null ? 0 : segments.size());

        // ---- Индекс существующих камер ----
        List<ChamberInfo> chamberInfos = new ArrayList<>();
        for (GeoObject chamber : chambers) {
            Geometry utmGeom = coordinateTransformer.toUtm37n(chamber.getGeometry());
            if (utmGeom == null) continue;
            chamberInfos.add(new ChamberInfo(chamber.getId(), utmGeom.getCoordinate()));
        }

        // Динамический учёт новых примыканий к существующим камерам (лимит ≤ 4).
        Map<String, Integer> dynamicNewTieInsCount = new HashMap<>();
        chamberIdCounter = 0;
        junctionIdCounter = 0;

        List<TieInResult> results = new ArrayList<>();

        // ============================================================
        // 1. Tie-ins для каждой ОКС: существующая или новая камера.
        // ============================================================
        for (RoutingService.Route route : routes) {
            TieInResult result = new TieInResult();
            result.oksId = route.oksId;
            result.endNodeId = route.endNodeId;
            result.isJunction = false;

            int routeDiameter = oksDiameters.getOrDefault(route.oksId, 0);
            result.newChamberDiameter = routeDiameter;

            Coordinate endCoord = route.endCoordinateUtm;
            if (endCoord == null) {
                log.warn("ОКС {}: нет координаты конечной точки → новая камера без координаты",
                        route.oksId);
                result.useExistingChamber = false;
                result.newChamberCoordinate = null;
                result.newChamberId = "v_chamber_" + (++chamberIdCounter);
                result.newChamberCost = calculateNewChamberCost(routeDiameter);
                result.tieInCost = 0;
                results.add(result);
                continue;
            }

            // Ищем существующую камеру в радиусе 10 м с учётом лимита ≤ 4 примыканий.
            ChamberInfo bestChamber = null;
            double bestDistance = Double.MAX_VALUE;

            for (ChamberInfo ci : chamberInfos) {
                double dist = endCoord.distance(ci.coordinate);
                if (dist > MAX_DISTANCE_TO_CHAMBER_M) continue;

                int baseExisting = countExistingTieIns(ci.coordinate, existingNetworks);
                int alreadyNew = dynamicNewTieInsCount.getOrDefault(ci.id, 0);
                int totalSimulated = baseExisting + alreadyNew;

                if (totalSimulated + 1 > MAX_TIE_INS) continue;

                if (dist < bestDistance) {
                    bestChamber = ci;
                    bestDistance = dist;
                }
            }

            if (bestChamber != null) {
                result.useExistingChamber = true;
                result.existingChamberId = bestChamber.id;
                result.tieInCost = TIE_IN_COST;
                result.existingChamberTieInCount = 1;
                dynamicNewTieInsCount.merge(bestChamber.id, 1, Integer::sum);

                log.info("ОКС {}: используем существующую камеру {} ({} м)",
                        route.oksId, bestChamber.id, Math.round(bestDistance));
            } else {
                result.useExistingChamber = false;
                result.newChamberCoordinate = endCoord;
                result.newChamberId = "v_chamber_" + (++chamberIdCounter);
                result.newChamberCost = calculateNewChamberCost(routeDiameter);
                result.tieInCost = 0;

                log.info("ОКС {}: новая камера ДУ {} стоимостью {}",
                        route.oksId, routeDiameter, Math.round(result.newChamberCost));
            }

            results.add(result);
        }

        // ============================================================
        // 2. Junction chambers: узлы со степенью >= 3, не являющиеся камерой.
        //    ТЗ п.2.1 — разветвления только в тепловых камерах.
        // ============================================================
        List<TieInResult> junctions = detectJunctionChambers(
                segments, results, chambers, chamberInfos);

        // Переименовываем узлы в сегментах: tn_XXX → ID новой junction-камеры.
        applyJunctionRenaming(segments, junctions);

        results.addAll(junctions);

        // ============================================================
        // 3. Объединение новых камер, оказавшихся в одной точке.
        // ============================================================
        List<TieInResult> mergedResults = mergeNewChambers(results);

        long existingCount = mergedResults.stream()
                .filter(r -> r.useExistingChamber).count();
        long newCount = mergedResults.stream()
                .filter(r -> !r.useExistingChamber).count();
        long junctionCount = mergedResults.stream()
                .filter(r -> r.isJunction).count();
        log.info("После объединения: {} врезок в существующие, {} новых камер (из них {} junction)",
                existingCount, newCount, junctionCount);

        return mergedResults;
    }

    /**
     * ТЗ п.2.1: «Разветвления выполняются только в тепловых камерах».
     *
     * Находит все узлы графа со степенью >= 3, которые НЕ являются:
     *   - ОКС-точками (start маршрутов),
     *   - существующими камерами,
     *   - уже созданными новыми камерами (по маршрутам ОКС).
     *
     * Для каждого такого узла создаёт junction-камеру:
     *   - если рядом (<= 10 м) есть существующая камера — использует её,
     *   - иначе создаёт новую heat_chamber с диаметром по наибольшему
     *     примыкающему участку (ТЗ п.3.2) и стоимостью по таблице 3.2.
     */
    private List<TieInResult> detectJunctionChambers(
            List<FlowCalculationService.CalculatedSegment> segments,
            List<TieInResult> existingTieIns,
            List<GeoObject> existingChambers,
            List<ChamberInfo> chamberInfos
    ) {
        List<TieInResult> junctions = new ArrayList<>();
        if (segments == null || segments.isEmpty()) return junctions;

        // --- Подсчёт степени каждого узла, координаты и макс. ДУ ---
        Map<String, Integer> degree = new HashMap<>();
        Map<String, Coordinate> coordById = new HashMap<>();
        Map<String, Integer> maxDiameterById = new HashMap<>();

        for (FlowCalculationService.CalculatedSegment s : segments) {
            if (s.geometry == null) continue;
            Coordinate start = s.geometry.getCoordinateN(0);
            Coordinate end   = s.geometry.getCoordinateN(s.geometry.getNumPoints() - 1);

            degree.merge(s.segmentStartNodeId, 1, Integer::sum);
            degree.merge(s.segmentEndNodeId,   1, Integer::sum);
            coordById.putIfAbsent(s.segmentStartNodeId, start);
            coordById.putIfAbsent(s.segmentEndNodeId,   end);
            maxDiameterById.merge(s.segmentStartNodeId, s.diameter, Math::max);
            maxDiameterById.merge(s.segmentEndNodeId,   s.diameter, Math::max);
        }

        // --- Легальные узлы: существующие камеры + точки присоединения ОКС ---
        Set<String> legalNodes = new HashSet<>();
        for (GeoObject c : existingChambers) legalNodes.add(c.getId());
        for (TieInResult t : existingTieIns) {
            // ОКС не повышаем до камеры.
            if (t.oksId != null) legalNodes.add(t.oksId);
            if (t.useExistingChamber && t.existingChamberId != null) {
                legalNodes.add(t.existingChamberId);
            }
            if (!t.useExistingChamber && t.newChamberId != null) {
                legalNodes.add(t.newChamberId);
            }
        }

        // --- Для каждого узла со степенью >= 3, не попавшего в legalNodes, ---
        // --- создаём junction-камеру.                                        ---
        for (Map.Entry<String, Integer> e : degree.entrySet()) {
            String nodeId = e.getKey();
            if (e.getValue() < JUNCTION_MIN_DEGREE) continue;
            if (legalNodes.contains(nodeId)) continue;

            // Нас интересуют только узлы графа (угловые tn_* и сетевые net_*).
            if (!nodeId.startsWith("tn_") && !nodeId.startsWith("net_")) continue;

            Coordinate coord = coordById.get(nodeId);
            if (coord == null) continue;

            // Если рядом есть существующая камера — используем её.
            ChamberInfo best = null;
            double bestDist = Double.MAX_VALUE;
            for (ChamberInfo ci : chamberInfos) {
                double d = coord.distance(ci.coordinate);
                if (d <= MAX_DISTANCE_TO_CHAMBER_M && d < bestDist) {
                    bestDist = d;
                    best = ci;
                }
            }

            TieInResult j = new TieInResult();
            j.oksId = null;
            j.endNodeId = nodeId;
            j.isJunction = true;

            if (best != null) {
                j.useExistingChamber = true;
                j.existingChamberId = best.id;
                // По ТЗ каждый линейный участок, заканчивающийся в существующей
                // камере, — отдельная врезка. У junction-камеры таких участков
                // столько, какова её степень.
                j.existingChamberTieInCount = e.getValue();
                j.tieInCost = TIE_IN_COST * e.getValue();

                log.info("Junction {} (degree={}): используем существующую камеру {} ({} м)",
                        nodeId, e.getValue(), best.id, Math.round(bestDist));
            } else {
                int diameter = maxDiameterById.getOrDefault(nodeId, 50);
                j.useExistingChamber = false;
                j.newChamberId = "v_junction_chamber_" + (++junctionIdCounter);
                j.newChamberCoordinate = coord;
                j.newChamberDiameter = diameter;
                j.newChamberCost = calculateNewChamberCost(diameter);
                j.tieInCost = 0;

                log.info("Junction {} (degree={}): новая камера ДУ {} стоимостью {}",
                        nodeId, e.getValue(), diameter, Math.round(j.newChamberCost));
            }

            junctions.add(j);
        }

        return junctions;
    }

    /**
     * Переименовывает segmentStartNodeId / segmentEndNodeId в сегментах:
     * для каждого узла, ставшего junction-камерой, ID меняется на
     * ID соответствующей камеры (новой или существующей).
     *
     * Это делается ДО передачи segments в costService и writer,
     * чтобы start_node_id / end_node_id в выходном GeoJSON ссылались
     * на реальный ID камеры.
     */
    private void applyJunctionRenaming(
            List<FlowCalculationService.CalculatedSegment> segments,
            List<TieInResult> junctions
    ) {
        if (segments == null || segments.isEmpty() || junctions.isEmpty()) return;

        Map<String, String> renameMap = new HashMap<>();
        for (TieInResult j : junctions) {
            String newId = j.useExistingChamber ? j.existingChamberId : j.newChamberId;
            if (newId != null && j.endNodeId != null) {
                renameMap.put(j.endNodeId, newId);
            }
        }
        if (renameMap.isEmpty()) return;

        for (FlowCalculationService.CalculatedSegment s : segments) {
            String a = renameMap.get(s.segmentStartNodeId);
            if (a != null) s.segmentStartNodeId = a;
            String b = renameMap.get(s.segmentEndNodeId);
            if (b != null) s.segmentEndNodeId = b;
        }
    }

    private List<TieInResult> mergeNewChambers(List<TieInResult> results) {
        List<TieInResult> merged = new ArrayList<>();
        Map<String, TieInResult> uniqueNewChambers = new LinkedHashMap<>();

        for (TieInResult result : results) {
            if (result.useExistingChamber) {
                merged.add(result);
                continue;
            }
            if (result.newChamberCoordinate == null) {
                merged.add(result);
                continue;
            }

            String key = Math.round(result.newChamberCoordinate.x / MERGE_PRECISION_M)
                    + "_" + Math.round(result.newChamberCoordinate.y / MERGE_PRECISION_M);

            TieInResult existing = uniqueNewChambers.get(key);
            if (existing == null) {
                uniqueNewChambers.put(key, result);
                merged.add(result);
            } else {
                // Оставляем максимальный ДУ и стоимость.
                if (result.newChamberDiameter > existing.newChamberDiameter) {
                    existing.newChamberDiameter = result.newChamberDiameter;
                    existing.newChamberCost = result.newChamberCost;
                }
                // Склеиваем oksId.
                if (existing.oksId == null) {
                    existing.oksId = result.oksId;
                } else if (result.oksId != null) {
                    existing.oksId = existing.oksId + "," + result.oksId;
                }
                // Пробрасываем флаг junction, если он есть.
                if (result.isJunction) existing.isJunction = true;

                log.info("Объединение камеры в точке ({}, {}): ОКС {} присоединена",
                        Math.round(result.newChamberCoordinate.x),
                        Math.round(result.newChamberCoordinate.y),
                        result.oksId);
            }
        }

        return merged;
    }

    private int countExistingTieIns(Coordinate chamberCoord, List<GeoObject> networks) {
        int count = 0;
        for (GeoObject net : networks) {
            Geometry utmGeom = coordinateTransformer.toUtm37n(net.getGeometry());
            if (utmGeom == null) continue;

            Coordinate[] coords = utmGeom.getCoordinates();

            for (Coordinate c : coords) {
                if (c.distance(chamberCoord) <= VERTEX_MATCH_TOLERANCE_M) {
                    count++;
                }
            }

            for (int i = 0; i < coords.length - 1; i++) {
                if (isPointOnSegment(chamberCoord, coords[i], coords[i + 1],
                        VERTEX_MATCH_TOLERANCE_M)) {
                    count += 2;
                }
            }
        }
        return count;
    }

    /**
     * Проверяет, лежит ли точка строго ВНУТРИ отрезка (не на его концах).
     * Точка, совпадающая с концом, считается примыканием через первую ветку
     * countExistingTieIns — иначе камера на вершине полилинии ошибочно
     * считалась бы «проходящей через два соседних сегмента» и давала +4.
     */
    private boolean isPointOnSegment(Coordinate p, Coordinate a, Coordinate b,
                                     double tolerance) {
        if (p.distance(a) < tolerance || p.distance(b) < tolerance) return false;

        double segmentLength = a.distance(b);
        if (segmentLength < tolerance) return false;
        double d1 = p.distance(a);
        double d2 = p.distance(b);
        return Math.abs(d1 + d2 - segmentLength) < tolerance;
    }

    /**
     * Стоимость новой камеры по таблице 3.2 ТЗ
     * (по наибольшему ДУ примыкающих участков).
     */
    private double calculateNewChamberCost(int diameter) {
        if (diameter <= 200)  return 3_000_000;
        if (diameter <= 500)  return 5_000_000;
        if (diameter <= 1000) return 8_000_000;
        return 12_000_000;
    }

    private static class ChamberInfo {
        final String id;
        final Coordinate coordinate;

        ChamberInfo(String id, Coordinate coordinate) {
            this.id = id;
            this.coordinate = coordinate;
        }
    }
}