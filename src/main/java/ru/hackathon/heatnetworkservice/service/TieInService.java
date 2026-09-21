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
import java.util.List;

/**
 * Сервис определения типа присоединения и подсчёта врезок.
 *
 * Правила (ТЗ + разъяснения 11, 12):
 * - Если маршрут заканчивается в существующей камере → врезка 5 000 000 руб.
 * - Если маршрут заканчивается в точке сети:
 *   - Если рядом (≤ 10 м) есть камера и после подключения примыканий ≤ 4 → используем камеру (5 000 000).
 *   - Иначе → новая камера по Таблице 3.2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TieInService {

    /** Максимальное расстояние от точки присоединения до существующей камеры (м). */
    private static final double MAX_DISTANCE_TO_CHAMBER_M = 10.0;

    /** Радиус проверки вершины сети на «совпадение» с камерой (м). */
    private static final double VERTEX_MATCH_TOLERANCE_M = 1.0;

    /** Максимальное количество примыканий к камере. */
    private static final int MAX_TIE_INS = 4;

    /** Стоимость одной врезки в существующую камеру. */
    private static final double TIE_IN_COST = 5_000_000.0;

    private final CoordinateTransformer coordinateTransformer;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public static class TieInResult {
        public String oksId;
        public String endNodeId;
        public boolean useExistingChamber;
        public String existingChamberId;
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
            List<FlowCalculationService.CalculatedSegment> segmentsByOks
    ) {
        log.info("Определение типа присоединения для {} маршрутов", routes.size());

        // Предвычислим UTM-координаты камер
        List<ChamberInfo> chamberInfos = new ArrayList<>();
        for (GeoObject chamber : chambers) {
            Geometry utmGeom = coordinateTransformer.toUtm37n(chamber.getGeometry());
            if (utmGeom == null) continue;
            chamberInfos.add(new ChamberInfo(chamber.getId(), utmGeom.getCoordinate()));
        }

        List<TieInResult> results = new ArrayList<>();
        for (RoutingService.Route route : routes) {
            TieInResult result = new TieInResult();
            result.oksId = route.oksId;
            result.endNodeId = route.endNodeId;

            // Определяем ДУ маршрута
            int routeDiameter = 0;
            for (FlowCalculationService.CalculatedSegment seg : segmentsByOks) {
                if (seg.oksId.equals(route.oksId)) {
                    routeDiameter = seg.diameter;
                    break;
                }
            }
            result.newChamberDiameter = routeDiameter;

            if (route.endIsChamber) {
                // Маршрут уже пришёл в существующую камеру
                result.useExistingChamber = true;
                result.existingChamberId = route.endNodeId;
                result.tieInCost = TIE_IN_COST;
                result.existingChamberTieInCount = 1;

                log.info("ОКС {}: маршрут в существующую камеру {} → врезка {}",
                        route.oksId, route.endNodeId, Math.round(TIE_IN_COST));
            } else {
                // Маршрут пришёл в точку сети. Ищем камеру в радиусе 10 м.
                Coordinate endCoord = route.endCoordinateUtm;
                if (endCoord == null) {
                    log.warn("ОКС {}: нет координаты конечной точки → новая камера", route.oksId);
                    result.useExistingChamber = false;
                    result.newChamberCoordinate = null;
                    result.newChamberCost = calculateNewChamberCost(routeDiameter);
                    result.tieInCost = 0;
                    results.add(result);
                    continue;
                }

                ChamberInfo bestChamber = null;
                double bestDistance = Double.MAX_VALUE;
                double minDistToChamber = Double.MAX_VALUE;
                String nearestChamberId = null;

                // ДИАГНОСТИКА: собираем расстояния до всех камер
                StringBuilder distances = new StringBuilder();
                for (ChamberInfo ci : chamberInfos) {
                    double dist = endCoord.distance(ci.coordinate);
                    distances.append(ci.id).append("=").append(Math.round(dist)).append("м ");

                    if (dist < minDistToChamber) {
                        minDistToChamber = dist;
                        nearestChamberId = ci.id;
                    }
                    if (dist <= MAX_DISTANCE_TO_CHAMBER_M && dist < bestDistance) {
                        int existingTieIns = countExistingTieIns(ci.coordinate, existingNetworks);
                        if (existingTieIns + 1 <= MAX_TIE_INS) {
                            bestChamber = ci;
                            bestDistance = dist;
                        }
                    }
                }

                log.info("ОКС {}: ближайшая камера {} в {} м (порог 10 м)",
                        route.oksId, nearestChamberId, Math.round(minDistToChamber));

                if (bestChamber != null) {
                    result.useExistingChamber = true;
                    result.existingChamberId = bestChamber.id;
                    result.tieInCost = TIE_IN_COST;
                    result.existingChamberTieInCount = 1;

                    log.info("ОКС {}: используем существующую камеру {} ({} м) → врезка {}",
                            route.oksId, bestChamber.id, Math.round(bestDistance),
                            Math.round(TIE_IN_COST));
                } else {
                    result.useExistingChamber = false;
                    result.newChamberCoordinate = endCoord;
                    result.newChamberCost = calculateNewChamberCost(routeDiameter);
                    result.tieInCost = 0;

                    log.info("ОКС {}: новая камера ДУ {} стоимостью {}",
                            route.oksId, routeDiameter, Math.round(result.newChamberCost));
                }
            }

            results.add(result);
        }

        long existingCount = results.stream().filter(r -> r.useExistingChamber).count();
        long newCount = results.stream().filter(r -> !r.useExistingChamber).count();
        log.info("Итого: {} врезок в существующие камеры, {} новых камер",
                existingCount, newCount);

        return results;
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
                Coordinate a = coords[i];
                Coordinate b = coords[i + 1];
                if (isPointOnSegment(chamberCoord, a, b, VERTEX_MATCH_TOLERANCE_M)) {
                    count += 2;
                }
            }
        }
        return count;
    }

    private boolean isPointOnSegment(Coordinate p, Coordinate a, Coordinate b, double tolerance) {
        double segmentLength = a.distance(b);
        if (segmentLength < tolerance) return false;

        double d1 = p.distance(a);
        double d2 = p.distance(b);

        return Math.abs(d1 + d2 - segmentLength) < tolerance;
    }

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