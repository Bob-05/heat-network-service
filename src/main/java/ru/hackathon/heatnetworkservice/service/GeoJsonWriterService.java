package ru.hackathon.heatnetworkservice.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.model.NewNetwork;
import ru.hackathon.heatnetworkservice.model.TieIn;
import ru.hackathon.heatnetworkservice.model.Variant;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeoJsonWriterService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Записывает результат в GeoJSON-файл.
     *
     * @param outputFile     путь к выходному файлу
     * @param variants       список вариантов
     * @param newNetworksMap новые участки по variantId
     * @param tieInsMap      точки врезки по variantId
     */
    public void writeResult(
            File outputFile,
            List<Variant> variants,
            Map<String, List<NewNetwork>> newNetworksMap,
            Map<String, List<TieIn>> tieInsMap
    ) throws IOException {

        log.info("Начинаем запись GeoJSON в файл: {}", outputFile.getAbsolutePath());

        try (FileOutputStream fos = new FileOutputStream(outputFile);
             JsonGenerator gen = objectMapper.getFactory().createGenerator(fos)) {
            gen.useDefaultPrettyPrinter();

            gen.writeStartObject();
            gen.writeStringField("type", "FeatureCollection");
            gen.writeStringField("name", "heat_network_result");
            gen.writeArrayFieldStart("features");

            for (Variant variant : variants) {
                String variantId = variant.getId();

                // Новые участки
                List<NewNetwork> newNetworks = newNetworksMap.getOrDefault(variantId, List.of());
                for (NewNetwork nn : newNetworks) {
                    writeNewNetwork(gen, nn);
                }

                // Точки врезки
                List<TieIn> tieIns = tieInsMap.getOrDefault(variantId, List.of());
                for (TieIn tieIn : tieIns) {
                    writeTieIn(gen, tieIn);
                }

                // Сводка по варианту
                writeVariantSummary(gen, variant);
            }

            gen.writeEndArray();
            gen.writeEndObject();
        }

        log.info("Запись GeoJSON завершена: {}", outputFile.getAbsolutePath());
    }

    private void writeNewNetwork(JsonGenerator gen, NewNetwork nn) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "Feature");

        gen.writeObjectFieldStart("geometry");
        writeGeometry(gen, nn.getGeometry());
        gen.writeEndObject();

        gen.writeObjectFieldStart("properties");
        gen.writeStringField("id", nn.getId());
        gen.writeStringField("object_type", "heat_network");
        gen.writeStringField("variant_id", nn.getVariantId());
        gen.writeStringField("start_node_id", nn.getStartNodeId());
        gen.writeStringField("end_node_id", nn.getEndNodeId());
        gen.writeNumberField("flow_tph", nn.getFlowTph());
        gen.writeNumberField("diameter", nn.getDiameter());
        gen.writeNumberField("length", nn.getLength());
        gen.writeStringField("laying_method", nn.getLayingMethod());
        if (nn.getDepthStart() != null) gen.writeNumberField("depth_start", nn.getDepthStart());
        else gen.writeNullField("depth_start");
        if (nn.getDepthEnd() != null) gen.writeNumberField("depth_end", nn.getDepthEnd());
        else gen.writeNullField("depth_end");
        gen.writeNumberField("cost", nn.getCost());
        gen.writeEndObject();

        gen.writeEndObject();
    }

    private void writeTieIn(JsonGenerator gen, TieIn tieIn) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "Feature");

        gen.writeObjectFieldStart("geometry");
        writeGeometry(gen, tieIn.getGeometry());
        gen.writeEndObject();

        gen.writeObjectFieldStart("properties");
        gen.writeStringField("id", tieIn.getId());
        gen.writeStringField("object_type", "heat_chamber");
        gen.writeStringField("variant_id", tieIn.getVariantId());
        if (tieIn.getRequiredDiameter() != null) gen.writeNumberField("diameter", tieIn.getRequiredDiameter());
        else gen.writeNullField("diameter");
        gen.writeNumberField("cost", tieIn.getCost());
        gen.writeEndObject();

        gen.writeEndObject();
    }

    private void writeVariantSummary(JsonGenerator gen, Variant variant) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "Feature");
        gen.writeNullField("geometry");

        gen.writeObjectFieldStart("properties");
        gen.writeStringField("id", "summary_" + variant.getId());
        gen.writeStringField("object_type", "variant_summary");
        gen.writeStringField("variant_id", variant.getId());
        if (variant.getRank() != null) gen.writeNumberField("rank", variant.getRank());
        if (variant.getConstructionCost() != null) gen.writeNumberField("construction_cost", variant.getConstructionCost());
        if (variant.getChamberConstructionCost() != null) gen.writeNumberField("chamber_construction_cost", variant.getChamberConstructionCost());
        if (variant.getExistingChamberTieInCount() != null) gen.writeNumberField("existing_chamber_tie_in_count", variant.getExistingChamberTieInCount());
        if (variant.getExistingChamberTieInCost() != null) gen.writeNumberField("existing_chamber_tie_in_cost", variant.getExistingChamberTieInCost());
        if (variant.getUnconnectedPenalty() != null) gen.writeNumberField("unconnected_penalty", variant.getUnconnectedPenalty());
        if (variant.getCalculatedCost() != null) gen.writeNumberField("calculated_cost", variant.getCalculatedCost());
        if (variant.getNewNetworkLength() != null) gen.writeNumberField("new_network_length", variant.getNewNetworkLength());
        if (variant.getScore() != null) gen.writeNumberField("score", variant.getScore());
        gen.writeArrayFieldStart("unconnected_oks_ids");
        gen.writeEndArray();
        gen.writeEndObject();

        gen.writeEndObject();
    }

    private void writeGeometry(JsonGenerator gen, Geometry geometry) throws IOException {
        if (geometry == null) {
            gen.writeNull();
            return;
        }

        gen.writeStringField("type", geometry.getGeometryType());

        switch (geometry.getGeometryType()) {
            case "Point":
                org.locationtech.jts.geom.Point point = (org.locationtech.jts.geom.Point) geometry;
                gen.writeArrayFieldStart("coordinates");
                gen.writeNumber(point.getX());
                gen.writeNumber(point.getY());
                gen.writeEndArray();
                break;

            case "LineString":
                org.locationtech.jts.geom.LineString line = (org.locationtech.jts.geom.LineString) geometry;
                gen.writeArrayFieldStart("coordinates");
                for (org.locationtech.jts.geom.Coordinate coord : line.getCoordinates()) {
                    gen.writeStartArray();
                    gen.writeNumber(coord.x);
                    gen.writeNumber(coord.y);
                    gen.writeEndArray();
                }
                gen.writeEndArray();
                break;

            case "Polygon":
                org.locationtech.jts.geom.Polygon polygon = (org.locationtech.jts.geom.Polygon) geometry;
                gen.writeArrayFieldStart("coordinates");
                writeRing(gen, polygon.getExteriorRing());
                for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                    writeRing(gen, polygon.getInteriorRingN(i));
                }
                gen.writeEndArray();
                break;

            default:
                log.warn("Неподдерживаемый тип геометрии: {}", geometry.getGeometryType());
                gen.writeNullField("coordinates");
        }
    }

    private void writeRing(JsonGenerator gen, org.locationtech.jts.geom.LineString ring) throws IOException {
        gen.writeStartArray();
        for (org.locationtech.jts.geom.Coordinate coord : ring.getCoordinates()) {
            gen.writeStartArray();
            gen.writeNumber(coord.x);
            gen.writeNumber(coord.y);
            gen.writeEndArray();
        }
        gen.writeEndArray();
    }
}