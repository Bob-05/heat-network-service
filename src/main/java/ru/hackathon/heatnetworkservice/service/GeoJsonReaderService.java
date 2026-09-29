package ru.hackathon.heatnetworkservice.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hackathon.heatnetworkservice.model.GeoObject;
import ru.hackathon.heatnetworkservice.repository.GeoObjectRepository;

import java.io.File;
import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeoJsonReaderService {

    private final GeoObjectRepository geoObjectRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Transactional
    public void readGeoJson(File file) throws IOException {
        log.info("Начинаем чтение GeoJSON: {}", file.getAbsolutePath());

        JsonFactory factory = objectMapper.getFactory();
        int count = 0;

        try (JsonParser parser = factory.createParser(file)) {
            while (parser.nextToken() != JsonToken.START_ARRAY) {
                // Пропускаем всё до массива features
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode feature = objectMapper.readTree(parser);
                GeoObject geoObject = parseFeature(feature);
                if (geoObject != null) {
                    geoObjectRepository.save(geoObject);
                    count++;
                    if (count % 1000 == 0) {
                        log.info("Обработано объектов: {}", count);
                    }
                }
            }
        }

        log.info("Чтение завершено. Всего обработано объектов: {}", count);
    }

    private GeoObject parseFeature(JsonNode feature) {
        try {
            JsonNode properties = feature.get("properties");
            JsonNode geometry = feature.get("geometry");

            if (properties == null || geometry == null) return null;

            GeoObject geoObject = new GeoObject();

            JsonNode idNode = properties.get("id");
            if (idNode == null || idNode.isNull()) {
                log.warn("Feature без id, object_type={}, пропускаем",
                        properties.has("object_type") ? properties.get("object_type").asText() : "?");
                return null;
            }
            if (idNode.isNumber()) {
                geoObject.setId(String.valueOf(idNode.asLong()));
                geoObject.setIdType("number");
            } else {
                geoObject.setId(idNode.asText());
                geoObject.setIdType("string");
            }

            if (!properties.has("object_type")) {
                log.warn("Feature {} без object_type, пропускаем", geoObject.getId());
                return null;
            }
            geoObject.setObjectType(properties.get("object_type").asText());

            if (properties.has("restriction_type")) {
                geoObject.setRestrictionType(properties.get("restriction_type").asText());
            }
            if (properties.has("diameter")) {
                geoObject.setDiameter(properties.get("diameter").asInt());
            }
            if (properties.has("flow_tph")) {
                geoObject.setFlowTph(properties.get("flow_tph").asDouble());
            }
            if (properties.has("heat_load")) {
                geoObject.setHeatLoad(properties.get("heat_load").asDouble());
            }
            if (properties.has("oks_id")) {
                geoObject.setOksId(properties.get("oks_id").asText());
            }
            if (properties.has("upstream_object_id")) {
                geoObject.setUpstreamObjectId(properties.get("upstream_object_id").asText());
            }

            geoObject.setProperties(properties.toString());

            Geometry geom = parseGeometry(geometry);
            if (geom != null) {
                geoObject.setGeometry(geom);
            }

            return geoObject;

        } catch (Exception e) {
            log.error("Ошибка парсинга feature: {}", e.getMessage());
            return null;
        }
    }

    private Geometry parseGeometry(JsonNode geometry) {
        String type = geometry.get("type").asText();

        switch (type) {
            case "Point":
                return parsePoint(geometry);
            case "LineString":
                return parseLineString(geometry);
            case "MultiLineString":
                return parseMultiLineString(geometry);
            case "Polygon":
                return parsePolygon(geometry);
            case "MultiPolygon":
                return parseMultiPolygon(geometry);
            default:
                log.warn("Неизвестный тип геометрии: {}", type);
                return null;
        }
    }

    private Point parsePoint(JsonNode geometry) {
        JsonNode coords = geometry.get("coordinates");
        double lon = coords.get(0).asDouble();
        double lat = coords.get(1).asDouble();
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        point.setSRID(4326);
        return point;
    }

    private LineString parseLineString(JsonNode geometry) {
        JsonNode coords = geometry.get("coordinates");
        LineString lineString = geometryFactory.createLineString(parseCoordinateArray(coords));
        lineString.setSRID(4326);
        return lineString;
    }

    private MultiLineString parseMultiLineString(JsonNode geometry) {
        JsonNode coords = geometry.get("coordinates");
        LineString[] lines = new LineString[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            lines[i] = geometryFactory.createLineString(parseCoordinateArray(coords.get(i)));
        }
        MultiLineString multi = geometryFactory.createMultiLineString(lines);
        multi.setSRID(4326);
        return multi;
    }

    private Polygon parsePolygon(JsonNode geometry) {
        JsonNode coords = geometry.get("coordinates");
        JsonNode exteriorRing = coords.get(0);
        Coordinate[] exteriorCoords = parseCoordinateArray(exteriorRing);
        LinearRing shell = geometryFactory.createLinearRing(exteriorCoords);

        LinearRing[] holes = new LinearRing[coords.size() - 1];
        for (int i = 1; i < coords.size(); i++) {
            holes[i - 1] = geometryFactory.createLinearRing(parseCoordinateArray(coords.get(i)));
        }

        Polygon polygon = geometryFactory.createPolygon(shell, holes);
        polygon.setSRID(4326);
        return polygon;
    }

    private MultiPolygon parseMultiPolygon(JsonNode geometry) {
        JsonNode coords = geometry.get("coordinates");
        Polygon[] polygons = new Polygon[coords.size()];

        for (int i = 0; i < coords.size(); i++) {
            JsonNode polygonCoords = coords.get(i);
            JsonNode exteriorRing = polygonCoords.get(0);
            Coordinate[] exteriorCoords = parseCoordinateArray(exteriorRing);
            LinearRing shell = geometryFactory.createLinearRing(exteriorCoords);

            LinearRing[] holes = new LinearRing[polygonCoords.size() - 1];
            for (int j = 1; j < polygonCoords.size(); j++) {
                holes[j - 1] = geometryFactory.createLinearRing(parseCoordinateArray(polygonCoords.get(j)));
            }
            polygons[i] = geometryFactory.createPolygon(shell, holes);
        }

        MultiPolygon multiPolygon = geometryFactory.createMultiPolygon(polygons);
        multiPolygon.setSRID(4326);
        return multiPolygon;
    }

    private Coordinate[] parseCoordinateArray(JsonNode array) {
        Coordinate[] coordinates = new Coordinate[array.size()];
        for (int i = 0; i < array.size(); i++) {
            JsonNode point = array.get(i);
            coordinates[i] = new Coordinate(point.get(0).asDouble(), point.get(1).asDouble());
        }
        return coordinates;
    }
}