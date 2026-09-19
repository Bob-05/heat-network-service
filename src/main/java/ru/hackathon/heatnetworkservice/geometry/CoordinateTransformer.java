package ru.hackathon.heatnetworkservice.geometry;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.*;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CoordinateTransformer {

    private static final String WGS84 = "EPSG:4326";
    private static final String UTM37N = "EPSG:32637";

    private final CoordinateTransform wgs84ToUtm37n;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public CoordinateTransformer() {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem sourceCrs = crsFactory.createFromName(WGS84);
        CoordinateReferenceSystem targetCrs = crsFactory.createFromName(UTM37N);

        CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();
        this.wgs84ToUtm37n = transformFactory.createTransform(sourceCrs, targetCrs);

        log.info("CoordinateTransformer инициализирован: {} → {}", WGS84, UTM37N);
    }

    /**
     * Преобразует геометрию из WGS84 (4326) в UTM37N (32637).
     */
    public Geometry toUtm37n(Geometry geometry) {
        if (geometry == null) {
            return null;
        }

        Geometry result;
        if (geometry instanceof Point) {
            result = transformPoint((Point) geometry);
        } else if (geometry instanceof LineString) {
            result = transformLineString((LineString) geometry);
        } else if (geometry instanceof Polygon) {
            result = transformPolygon((Polygon) geometry);
        } else if (geometry instanceof MultiPolygon) {
            result = transformMultiPolygon((MultiPolygon) geometry);
        } else {
            throw new IllegalArgumentException("Неизвестный тип геометрии: " + geometry.getGeometryType());
        }

        result.setSRID(32637);
        return result;
    }

    private Coordinate transformCoordinate(Coordinate coord) {
        ProjCoordinate source = new ProjCoordinate(coord.x, coord.y);
        ProjCoordinate target = new ProjCoordinate();
        wgs84ToUtm37n.transform(source, target);
        return new Coordinate(target.x, target.y);
    }

    private Point transformPoint(Point point) {
        return geometryFactory.createPoint(transformCoordinate(point.getCoordinate()));
    }

    private LineString transformLineString(LineString lineString) {
        Coordinate[] coords = new Coordinate[lineString.getNumPoints()];
        for (int i = 0; i < coords.length; i++) {
            coords[i] = transformCoordinate(lineString.getCoordinateN(i));
        }
        return geometryFactory.createLineString(coords);
    }

    private Polygon transformPolygon(Polygon polygon) {
        LinearRing shell = transformLinearRing((LinearRing) polygon.getExteriorRing());

        LinearRing[] holes = new LinearRing[polygon.getNumInteriorRing()];
        for (int i = 0; i < holes.length; i++) {
            holes[i] = transformLinearRing((LinearRing) polygon.getInteriorRingN(i));
        }

        return geometryFactory.createPolygon(shell, holes);
    }

    private MultiPolygon transformMultiPolygon(MultiPolygon multiPolygon) {
        Polygon[] polygons = new Polygon[multiPolygon.getNumGeometries()];
        for (int i = 0; i < polygons.length; i++) {
            polygons[i] = transformPolygon((Polygon) multiPolygon.getGeometryN(i));
        }
        return geometryFactory.createMultiPolygon(polygons);
    }

    private LinearRing transformLinearRing(LinearRing ring) {
        Coordinate[] coords = new Coordinate[ring.getNumPoints()];
        for (int i = 0; i < coords.length; i++) {
            coords[i] = transformCoordinate(ring.getCoordinateN(i));
        }
        return geometryFactory.createLinearRing(coords);
    }
}