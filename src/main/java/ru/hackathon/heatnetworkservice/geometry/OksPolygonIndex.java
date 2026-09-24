package ru.hackathon.heatnetworkservice.geometry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Индекс: для каждой точки подключения ОКС — свой oks-полигон.
 *
 * По ТЗ (раздел 4):
 * - Полигон oks — непроходимое препятствие.
 * - Исключение: один финальный прямой участок к точке подключения
 *   внутри СВОЕГО полигона.
 * - Чужие oks-полигоны — запрещены.
 *
 * ВАЖНО: contains() возвращает false, если точка лежит ровно на границе.
 * WGS84 → UTM37N даёт погрешность до 1 м, поэтому используем допуск.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OksPolygonIndex {

    /** Допуск на границе полигона (м). */
    private static final double BOUNDARY_TOLERANCE_M = 1.0;

    private final CoordinateTransformer coordinateTransformer;

    private Map<String, Geometry> oksOwnPolygons = new HashMap<>();
    private Map<String, Point> oksPointMap = new HashMap<>();

    public void build(List<GeoObject> oksPointObjects, List<GeoObject> oksRestrictions) {
        log.info("Построение OksPolygonIndex: {} точек ОКС, {} oks-полигонов",
                oksPointObjects.size(), oksRestrictions.size());

        List<Geometry> oksPolygonsUtm = new ArrayList<>();
        for (GeoObject restriction : oksRestrictions) {
            if (!"oks".equals(restriction.getRestrictionType())) continue;
            if (restriction.getGeometry() == null) continue;

            Geometry utm = coordinateTransformer.toUtm37n(restriction.getGeometry());
            if (utm != null) {
                oksPolygonsUtm.add(utm);
            }
        }

        this.oksOwnPolygons = new HashMap<>();
        this.oksPointMap = new HashMap<>();

        int found = 0;
        int notFound = 0;

        for (GeoObject oks : oksPointObjects) {
            if (oks.getGeometry() == null) continue;

            Geometry oksUtm = coordinateTransformer.toUtm37n(oks.getGeometry());
            if (!(oksUtm instanceof Point)) continue;

            Point oksPoint = (Point) oksUtm;
            oksPointMap.put(oks.getId(), oksPoint);

            Geometry ownPolygon = null;
            for (Geometry polygon : oksPolygonsUtm) {
                // Учитываем границу полигона с допуском
                if (polygon.contains(oksPoint)
                        || polygon.distance(oksPoint) <= BOUNDARY_TOLERANCE_M) {
                    ownPolygon = polygon;
                    break;
                }
            }

            if (ownPolygon != null) {
                oksOwnPolygons.put(oks.getId(), ownPolygon);
                found++;
            } else {
                notFound++;
                log.warn("Для ОКС {} не найден свой oks-полигон", oks.getId());
            }
        }

        log.info("OksPolygonIndex готов: {} ОКС имеют свой полигон, {} без полигона",
                found, notFound);
    }

    public Geometry getOwnPolygon(String oksId) {
        return oksOwnPolygons.get(oksId);
    }

    public boolean isOwnPolygon(String oksId, Geometry polygon) {
        Geometry own = oksOwnPolygons.get(oksId);
        if (own == null || polygon == null) return false;
        return own.equalsTopo(polygon);
    }

    public Point getOksPoint(String oksId) {
        return oksPointMap.get(oksId);
    }

    public boolean isPointInsideOwnPolygon(String oksId) {
        Point oksPoint = oksPointMap.get(oksId);
        Geometry ownPolygon = oksOwnPolygons.get(oksId);
        if (oksPoint == null || ownPolygon == null) return false;
        return ownPolygon.contains(oksPoint)
                || ownPolygon.distance(oksPoint) <= BOUNDARY_TOLERANCE_M;
    }

    /** Возвращает true, если у ОКС вообще есть свой полигон. */
    public boolean hasOwnPolygon(String oksId) {
        return oksOwnPolygons.containsKey(oksId);
    }
}