package ru.hackathon.heatnetworkservice.geometry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Индекс: для каждой точки подключения ОКС — набор её oks-полигонов.
 *
 * ТЗ раздел 4:
 *  - Полигон oks — непроходимое препятствие.
 *  - Исключение: один финальный прямой участок к точке подключения
 *    внутри СВОЕГО полигона.
 *  - Чужие oks-полигоны — запрещены.
 *
 * Особенности реализации:
 *  - Одна OKS-точка может быть окружена НЕСКОЛЬКИМИ полигонами
 *    (например, MultiPolygon, разрезанный на подполигоны, или
 *    перекрывающиеся территориальные зоны). Все они считаются
 *    «своими» для этой точки.
 *  - Порядок сортировки: сначала самые маленькие по площади — они
 *    наиболее вероятно являются «собственным» контуром здания, а не
 *    общей зоной.
 *  - contains() возвращает false, если точка лежит ровно на границе.
 *    WGS84 → UTM37N даёт погрешность до 1 м, поэтому используем допуск.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OksPolygonIndex {

    private static final double BOUNDARY_TOLERANCE_M = 1.0;

    private final CoordinateTransformer coordinateTransformer;

    /** OKS id → список всех «своих» полигонов (в UTM37N). */
    private Map<String, List<Geometry>> oksOwnPolygons = new HashMap<>();
    private Map<String, Point> oksPointMap = new HashMap<>();

    public void build(List<GeoObject> oksPointObjects, List<GeoObject> oksRestrictions) {
        log.info("Построение OksPolygonIndex: {} точек ОКС, {} oks-полигонов",
                oksPointObjects.size(), oksRestrictions.size());

        List<Geometry> oksPolygonsUtm = new ArrayList<>();
        for (GeoObject restriction : oksRestrictions) {
            if (!"oks".equals(restriction.getRestrictionType())) continue;
            if (restriction.getGeometry() == null) continue;

            Geometry utm = coordinateTransformer.toUtm37n(restriction.getGeometry());
            if (utm != null) oksPolygonsUtm.add(utm);
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

            // Все полигоны, содержащие точку (с допуском на границе).
            List<Geometry> own = new ArrayList<>();
            for (Geometry polygon : oksPolygonsUtm) {
                if (polygon.contains(oksPoint)
                        || polygon.distance(oksPoint) <= BOUNDARY_TOLERANCE_M) {
                    own.add(polygon);
                }
            }

            if (!own.isEmpty()) {
                // Сортируем: сначала маленькие полигоны — они наиболее вероятно
                // являются собственным контуром ОКС, а не общей зоной.
                own.sort(Comparator.comparingDouble(Geometry::getArea));
                oksOwnPolygons.put(oks.getId(), own);
                found++;
                if (own.size() > 1) {
                    // Java 11: .toList() недоступен, используем Collectors.toList().
                    List<Long> areas = own.stream()
                            .map(p -> Math.round(p.getArea()))
                            .collect(Collectors.toList());
                    log.debug("OKS {}: найдено {} полигонов (площади: {})",
                            oks.getId(), own.size(), areas);
                }
            } else {
                notFound++;
                log.warn("Для ОКС {} не найден свой oks-полигон", oks.getId());
            }
        }

        log.info("OksPolygonIndex готов: {} ОКС имеют свой полигон, {} без полигона",
                found, notFound);
    }

    /** Возвращает «главный» (наименьший по площади) полигон OKS, либо null. */
    public Geometry getOwnPolygon(String oksId) {
        List<Geometry> own = oksOwnPolygons.get(oksId);
        return (own == null || own.isEmpty()) ? null : own.get(0);
    }

    /** Возвращает все «свои» полигоны OKS. */
    public List<Geometry> getOwnPolygons(String oksId) {
        List<Geometry> own = oksOwnPolygons.get(oksId);
        return own != null ? own : java.util.Collections.emptyList();
    }

    /**
     * Проверяет, является ли данный полигон «своим» для указанной OKS.
     * Сравнение — топологическое равенство, но проверяются ВСЕ полигоны
     * из списка, а не только первый.
     */
    public boolean isOwnPolygon(String oksId, Geometry polygon) {
        if (polygon == null) return false;
        List<Geometry> own = oksOwnPolygons.get(oksId);
        if (own == null || own.isEmpty()) return false;
        for (Geometry p : own) {
            if (p.equalsTopo(polygon)) return true;
        }
        return false;
    }

    public Point getOksPoint(String oksId) {
        return oksPointMap.get(oksId);
    }

    public boolean isPointInsideOwnPolygon(String oksId) {
        Point oksPoint = oksPointMap.get(oksId);
        List<Geometry> own = oksOwnPolygons.get(oksId);
        if (oksPoint == null || own == null || own.isEmpty()) return false;
        for (Geometry p : own) {
            if (p.contains(oksPoint) || p.distance(oksPoint) <= BOUNDARY_TOLERANCE_M) {
                return true;
            }
        }
        return false;
    }

    public boolean hasOwnPolygon(String oksId) {
        List<Geometry> own = oksOwnPolygons.get(oksId);
        return own != null && !own.isEmpty();
    }
}