package ru.hackathon.heatnetworkservice.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
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
            // Ищем начало FeatureCollection
            while (parser.nextToken() != JsonToken.START_ARRAY) {
                // Пропускаем всё до массива features
            }

            // Читаем каждый Feature
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

            if (properties == null || geometry == null) {
                return null;
            }

            GeoObject geoObject = new GeoObject();
            geoObject.setId(properties.get("id").asText());
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

            // Пока сохраняем геометрию как точку (для простоты)
            // Позже добавим LineString, Polygon и т.д.
            if ("Point".equals(geometry.get("type").asText())) {
                JsonNode coords = geometry.get("coordinates");
                double lon = coords.get(0).asDouble();
                double lat = coords.get(1).asDouble();
                Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
                point.setSRID(4326);
                geoObject.setGeometry(point);
            }

            return geoObject;

        } catch (Exception e) {
            log.error("Ошибка парсинга feature: {}", e.getMessage());
            return null;
        }
    }
}