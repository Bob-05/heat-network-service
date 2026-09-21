package ru.hackathon.heatnetworkservice.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.geometry.CoordinateTransformer;
import ru.hackathon.heatnetworkservice.geometry.GraphBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeoJsonWriterService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final CoordinateTransformer coordinateTransformer;

    /**
     * Записывает список вариантов в GeoJSON-файл.
     */
    public void writeVariants(File outputFile, List<VariantService.Variant> variants) throws IOException {
        log.info("Запись GeoJSON: {} вариантов в {}", variants.size(), outputFile.getAbsolutePath());

        try (FileOutputStream fos = new FileOutputStream(outputFile);
             JsonGenerator gen = objectMapper.getFactory().createGenerator(fos)) {

            gen.useDefaultPrettyPrinter();
            gen.writeStartObject();
            gen.writeStringField("type", "FeatureCollection");
            gen.writeStringField("name", "heat_network_result");
            gen.writeArrayFieldStart("features");

            for (VariantService.Variant variant : variants) {
                writeVariant(gen, variant);
            }

            gen.writeEndArray();
            gen.writeEndObject();
        }

        log.info("GeoJSON записан: {}", outputFile.getAbsolutePath());
    }

    private void writeVariant(JsonGenerator gen, VariantService.Variant variant) throws IOException {
        String variantId = variant.variantId;

        // 1. Участки heat_network
        int netIndex = 0;
        for (FlowCalculationService.CalculatedSegment seg : variant.segments) {
            netIndex++;
            writeNetworkSegment(gen, variantId, netIndex, seg);
        }

        // 2. Технические узлы (границы спецпроходов)
        writeTechnicalNodes(gen, variantId, variant);

        // 3. Новые камеры
        int chamberIndex = 0;
        for (TieInService.TieInResult tieIn : variant.cost.tieIns) {
            if (!tieIn.useExistingChamber) {
                chamberIndex++;
                writeNewChamber(gen, variantId, chamberIndex, tieIn);
            }
        }

        // 4. Сводка
        writeVariantSummary(gen, variant);
    }

    private void writeNetworkSegment(JsonGenerator gen, String variantId, int index,
                                     FlowCalculationService.CalculatedSegment seg) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "Feature");

        gen.writeObjectFieldStart("geometry");
        writeGeometry(gen, toWgs84(seg.geometry));
        gen.writeEndObject();

        gen.writeObjectFieldStart("properties");
        gen.writeStringField("id", variantId + "_net_" + index);
        gen.writeStringField("object_type", "heat_network");
        gen.writeStringField("variant_id", variantId);
        gen.writeStringField("start_node_id", seg.segmentStartNodeId);
        gen.writeStringField("end_node_id", seg.segmentEndNodeId);
        gen.writeNumberField("flow_tph", round(seg.flowTph, 2));
        gen.writeNumberField("diameter", seg.diameter);
        gen.writeNumberField("length", round(seg.length, 2));
        gen.writeStringField("laying_method", seg.layingMethod);
        gen.writeNullField("depth_start");
        gen.writeNullField("depth_end");
        gen.writeNumberField("cost", round(seg.cost, 2));
        gen.writeEndObject();

        gen.writeEndObject();
    }

    private void writeNewChamber(JsonGenerator gen, String variantId, int index,
                                 TieInService.TieInResult tieIn) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "Feature");

        gen.writeObjectFieldStart("geometry");
        Point utmPoint = geometryFactory.createPoint(tieIn.newChamberCoordinate);
        utmPoint.setSRID(32637);
        writeGeometry(gen, toWgs84(utmPoint));
        gen.writeEndObject();

        gen.writeObjectFieldStart("properties");
        gen.writeStringField("id", variantId + "_chamber_" + index);
        gen.writeStringField("object_type", "heat_chamber");
        gen.writeStringField("variant_id", variantId);
        gen.writeNumberField("diameter", tieIn.newChamberDiameter);
        gen.writeNumberField("cost", round(tieIn.newChamberCost, 2));
        gen.writeEndObject();

        gen.writeEndObject();
    }

    private void writeTechnicalNodes(JsonGenerator gen, String variantId,
                                     VariantService.Variant variant) throws IOException {
        int index = 0;

        for (RoutingService.Route route : variant.routes) {
            List<GraphBuilder.Edge> edges = route.edges;
            for (int i = 0; i < edges.size() - 1; i++) {
                GraphBuilder.Edge current = edges.get(i);
                GraphBuilder.Edge next = edges.get(i + 1);

                boolean methodChanged = !current.layingMethod.equals(next.layingMethod);
                if (!methodChanged) continue;

                Coordinate boundaryUtm = current.toCoordinateUtm;
                if (isOksOrChamber(boundaryUtm, variant)) continue;

                index++;
                gen.writeStartObject();
                gen.writeStringField("type", "Feature");

                gen.writeObjectFieldStart("geometry");
                Point utmPoint = geometryFactory.createPoint(boundaryUtm);
                utmPoint.setSRID(32637);
                writeGeometry(gen, toWgs84(utmPoint));
                gen.writeEndObject();

                gen.writeObjectFieldStart("properties");
                gen.writeStringField("id", variantId + "_tn_" + index);
                gen.writeStringField("object_type", "technical_node");
                gen.writeStringField("variant_id", variantId);
                gen.writeEndObject();

                gen.writeEndObject();
            }
        }
    }

    private boolean isOksOrChamber(Coordinate coordUtm, VariantService.Variant variant) {
        for (var oks : variant.allOks) {
            Geometry utmGeom = coordinateTransformer.toUtm37n(oks.getGeometry());
            if (utmGeom != null && utmGeom.getCoordinate().distance(coordUtm) < 1.0) return true;
        }
        for (var chamber : variant.chambers) {
            Geometry utmGeom = coordinateTransformer.toUtm37n(chamber.getGeometry());
            if (utmGeom != null && utmGeom.getCoordinate().distance(coordUtm) < 1.0) return true;
        }
        return false;
    }

    private void writeVariantSummary(JsonGenerator gen, VariantService.Variant variant) throws IOException {
        CostService.CostResult cost = variant.cost;

        gen.writeStartObject();
        gen.writeStringField("type", "Feature");
        gen.writeNullField("geometry");

        gen.writeObjectFieldStart("properties");
        gen.writeStringField("id", variant.variantId + "_summary");
        gen.writeStringField("object_type", "variant_summary");
        gen.writeStringField("variant_id", variant.variantId);
        gen.writeNumberField("rank", variant.rank);
        gen.writeNumberField("construction_cost", round(cost.constructionCost, 2));
        gen.writeNumberField("chamber_construction_cost", round(cost.chamberConstructionCost, 2));
        gen.writeNumberField("existing_chamber_tie_in_count", cost.existingChamberTieInCount);
        gen.writeNumberField("existing_chamber_tie_in_cost", round(cost.existingChamberTieInCost, 2));
        gen.writeNumberField("unconnected_penalty", round(cost.unconnectedPenalty, 2));
        gen.writeNumberField("calculated_cost", round(cost.calculatedCost, 2));
        gen.writeNumberField("new_network_length", round(cost.newNetworkLength, 2));
        gen.writeNumberField("score", round(cost.score, 4));

        gen.writeArrayFieldStart("unconnected_oks_ids");
        for (String oksId : cost.unconnectedOksIds) {
            gen.writeString(oksId);
        }
        gen.writeEndArray();

        gen.writeEndObject();
        gen.writeEndObject();
    }

    private Geometry toWgs84(Geometry utmGeometry) {
        if (utmGeometry == null) return null;
        return coordinateTransformer.toWgs84(utmGeometry);
    }

    private void writeGeometry(JsonGenerator gen, Geometry geometry) throws IOException {
        if (geometry == null) {
            gen.writeNull();
            return;
        }

        gen.writeStringField("type", geometry.getGeometryType());

        switch (geometry.getGeometryType()) {
            case "Point":
                Point point = (Point) geometry;
                gen.writeArrayFieldStart("coordinates");
                gen.writeNumber(point.getX());
                gen.writeNumber(point.getY());
                gen.writeEndArray();
                break;

            case "LineString":
                LineString line = (LineString) geometry;
                gen.writeArrayFieldStart("coordinates");
                for (Coordinate coord : line.getCoordinates()) {
                    gen.writeStartArray();
                    gen.writeNumber(coord.x);
                    gen.writeNumber(coord.y);
                    gen.writeEndArray();
                }
                gen.writeEndArray();
                break;

            default:
                log.warn("Неподдерживаемый тип геометрии: {}", geometry.getGeometryType());
                gen.writeNullField("coordinates");
        }
    }

    private double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}