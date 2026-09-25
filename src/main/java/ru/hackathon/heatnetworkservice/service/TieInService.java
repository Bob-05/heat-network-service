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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис определения типа присоединения и подсчёта врезок.
 *
 * Правила (ТЗ п.2.4 + разъяснения 11, 12):
 * - Если маршрут заканчивается в точке сети:
 *   - Если рядом (≤ 10 м) есть существующая камера и после подключения
 *     к камере будет примыкать не более 4 линейных участков — используем её.
 *     Стоимость одной врезки 5 000 000 руб.
 *   - Иначе — новая камера в конечной точке сети, стоимость по таблице 3.2.
 *
 * ВАЖНО: подсчёт примыканий учитывает как существующие, так и новые,
 * созданные в рамках текущего построения.
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

    private final CoordinateTransformer coordinateTransformer;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    private int chamberIdCounter = 0;

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
    }

    public List<TieInResult> determineTieIns(
            List<RoutingService.Route> routes,
            List<GeoObject> chambers,
            List<GeoObject> existingNetworks,
            Map<String, Integer> oksDiameters
    ) {
        log.info("Определение типа присоединения для {} маршрутов", routes.size());

        List<ChamberInfo> chamberInfos = new ArrayList<>();
        for (GeoObject chamber : chambers) {
            Geometry utmGeom = coordinateTransformer.toUtm37n(chamber.getGeometry());
            if (utmGeom == null) continue;
            chamberInfos.add(new ChamberInfo(chamber.getId(), utmGeom.getCoordinate()));
        }

        // Динамический учёт новых примыканий к существующим камерам в рамках
        // текущего построения — для соблюдения лимита ≤4.
        Map<String, Integer> dynamicNewTieInsCount = new HashMap<>();
        chamberIdCounter = 0;

        List<TieInResult> results = new ArrayList<>();
        for (RoutingService.Route route : routes) {
            TieInResult result = new TieInResult();
            result.oksId = route.oksId;
            result.endNodeId = route.endNodeId;

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

            // Ищем существующую камеру в радиусе 10 м с учётом лимита ≤4 примыканий.
            ChamberInfo bestChamber = null;
            double bestDistance = Double.MAX_VALUE;

            for (ChamberInfo ci : chamberInfos) {
                double dist = endCoord.distance(ci.coordinate);
                if (dist > MAX_DISTANCE_TO_CHAMBER_M) continue;

                int baseExisting = countExistingTieIns(ci.coordinate, existingNetworks);
                int alreadyNew = dynamicNewTieInsCount.getOrDefault(ci.id, 0);
                int totalSimulated = baseExisting + alreadyNew;

                // После подключения текущей ОКС должно быть ≤ MAX_TIE_INS примыканий.
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

        // Объединение новых камер, оказавшихся в одной точке.
        List<TieInResult> mergedResults = mergeNewChambers(results);

        long existingCount = mergedResults.stream()
                .filter(r -> r.useExistingChamber).count();
        long newCount = mergedResults.stream()
                .filter(r -> !r.useExistingChamber).count();
        log.info("После объединения: {} врезок в существующие, {} новых камер",
                existingCount, newCount);

        return mergedResults;
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
                if (result.newChamberDiameter > existing.newChamberDiameter) {
                    existing.newChamberDiameter = result.newChamberDiameter;
                    existing.newChamberCost = result.newChamberCost;
                }
                existing.oksId = existing.oksId + "," + result.oksId;
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
        if (diameter <= 200) return 3_000_000;
        if (diameter <= 500) return 5_000_000;
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