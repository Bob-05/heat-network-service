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
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeoJsonWriterService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final CoordinateTransformer coordinateTransformer;

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

        // Карта маппинга net_... → ID камеры / new chamber
        Map<String, String> nodeIdMap = buildNodeIdMap(variant);

        // 1. Слияние последовательных сегментов в непрерывные LineString
        //    (ТЗ п.2.1 и п.7.2: повороты без изменения параметров —
        //     внутренние вершины LineString, а не отдельные участки).
        List<FlowCalculationService.CalculatedSegment> mergedSegments =
                mergeConsecutiveSegments(variant.segments, variant);

        // 2. Участки heat_network
        int netIndex = 0;
        for (FlowCalculationService.CalculatedSegment seg : mergedSegments) {
            netIndex++;
            writeNetworkSegment(gen, variantId, netIndex, seg, nodeIdMap);
        }

        // 3. Технические узлы
        writeTechnicalNodes(gen, variantId, variant, nodeIdMap);

        // 4. Новые камеры
        int chamberIndex = 0;
        for (TieInService.TieInResult tieIn : variant.cost.tieIns) {
            if (!tieIn.useExistingChamber) {
                chamberIndex++;
                writeNewChamber(gen, variantId, chamberIndex, tieIn);
            }
        }

        // 5. Сводка
        writeVariantSummary(gen, variant);
    }

    /**
     * Слияние последовательных сегментов в непрерывные LineString
     * между узлами остановки.
     *
     * ТЗ п.2.1: «Поворот без изменения параметров задаётся вершиной
     * линейной геометрии (LineString) и отдельного узла не требует.»
     *
     * ТЗ п.7.2: «Поворот без изменения параметров остаётся внутренней
     * вершиной LineString.»
     *
     * Узлы остановки:
     *   - ОКС-точки (start/end всей цепочки);
     *   - существующие и новые камеры (там заканчивается маршрут);
     *   - узлы, где меняется ДУ между соседними сегментами;
     *   - узлы, где меняется laying_method;
     *   - узлы разветвления (степень ≥ 3) — чтобы не путать разные ветки.
     *
     * Условие склейки двух соседних сегментов:
     *   - общий узел не является узлом остановки;
     *   - одинаковый diameter;
     *   - одинаковый laying_method;
     *   - одинаковый flow_tph (иначе на узле присоединилась ещё одна ОКС,
     *     и объединение изменило бы смысл атрибута «расчётный расход участка»).
     */
    private List<FlowCalculationService.CalculatedSegment> mergeConsecutiveSegments(
            List<FlowCalculationService.CalculatedSegment> segments,
            VariantService.Variant variant
    ) {
        if (segments == null || segments.isEmpty()) return segments;

        // 1. Инцидентность: узел → список сегментов, которые его касаются
        Map<String, List<FlowCalculationService.CalculatedSegment>> incident = new HashMap<>();
        for (FlowCalculationService.CalculatedSegment seg : segments) {
            incident.computeIfAbsent(seg.segmentStartNodeId, k -> new ArrayList<>()).add(seg);
            incident.computeIfAbsent(seg.segmentEndNodeId, k -> new ArrayList<>()).add(seg);
        }

        // 2. Множество узлов остановки
        Set<String> stopNodes = new HashSet<>();

        for (GeoObject oks : variant.allOks) {
            stopNodes.add(oks.getId());
        }
        for (GeoObject chamber : variant.chambers) {
            stopNodes.add(chamber.getId());
        }
        for (TieInService.TieInResult tieIn : variant.cost.tieIns) {
            if (!tieIn.useExistingChamber && tieIn.newChamberId != null) {
                stopNodes.add(tieIn.newChamberId);
            }
        }

        for (Map.Entry<String, List<FlowCalculationService.CalculatedSegment>> e : incident.entrySet()) {
            List<FlowCalculationService.CalculatedSegment> list = e.getValue();
            if (list.size() >= 3) {
                // Развилка. Склеивать через неё нельзя, иначе потеряем структуру.
                stopNodes.add(e.getKey());
                continue;
            }
            if (list.size() == 2) {
                FlowCalculationService.CalculatedSegment a = list.get(0);
                FlowCalculationService.CalculatedSegment b = list.get(1);
                if (a.diameter != b.diameter
                        || !Objects.equals(a.layingMethod, b.layingMethod)) {
                    stopNodes.add(e.getKey());
                }
            }
        }

        // 3. Строим цепочки
        Set<FlowCalculationService.CalculatedSegment> used = new HashSet<>();
        List<FlowCalculationService.CalculatedSegment> result = new ArrayList<>();

        for (FlowCalculationService.CalculatedSegment seg : segments) {
            if (used.contains(seg)) continue;

            List<FlowCalculationService.CalculatedSegment> chain = new ArrayList<>();
            chain.add(seg);
            used.add(seg);

            // 3а. Идём вперёд (по segmentEndNodeId)
            String currentEnd = seg.segmentEndNodeId;
            while (!stopNodes.contains(currentEnd)) {
                FlowCalculationService.CalculatedSegment next =
                        findNextSegment(segments, used, currentEnd, seg);
                if (next == null) break;

                if (next.segmentEndNodeId.equals(currentEnd)) {
                    reverseSegmentGeometry(next);
                    swapEnds(next);
                }
                chain.add(next);
                used.add(next);
                currentEnd = next.segmentEndNodeId;
            }

            // 3б. Идём назад (по segmentStartNodeId)
            String currentStart = chain.get(0).segmentStartNodeId;
            while (!stopNodes.contains(currentStart)) {
                FlowCalculationService.CalculatedSegment prev =
                        findPrevSegment(segments, used, currentStart, chain.get(0));
                if (prev == null) break;

                if (prev.segmentStartNodeId.equals(currentStart)) {
                    reverseSegmentGeometry(prev);
                    swapEnds(prev);
                }
                chain.add(0, prev);
                used.add(prev);
                currentStart = prev.segmentStartNodeId;
            }

            // 3в. Собираем итоговый сегмент
            result.add(buildMergedSegment(chain));
        }

        log.info("Слияние сегментов: {} → {}", segments.size(), result.size());
        return result;
    }

    private FlowCalculationService.CalculatedSegment findNextSegment(
            List<FlowCalculationService.CalculatedSegment> all,
            Set<FlowCalculationService.CalculatedSegment> used,
            String nodeId,
            FlowCalculationService.CalculatedSegment template
    ) {
        for (FlowCalculationService.CalculatedSegment s : all) {
            if (used.contains(s)) continue;
            if (s == template) continue;
            boolean touches = s.segmentStartNodeId.equals(nodeId)
                    || s.segmentEndNodeId.equals(nodeId);
            if (!touches) continue;
            if (s.diameter != template.diameter) continue;
            if (!Objects.equals(s.layingMethod, template.layingMethod)) continue;
            if (Double.compare(s.flowTph, template.flowTph) != 0) continue;
            return s;
        }
        return null;
    }

    private FlowCalculationService.CalculatedSegment findPrevSegment(
            List<FlowCalculationService.CalculatedSegment> all,
            Set<FlowCalculationService.CalculatedSegment> used,
            String nodeId,
            FlowCalculationService.CalculatedSegment template
    ) {
        return findNextSegment(all, used, nodeId, template);
    }

    private void reverseSegmentGeometry(FlowCalculationService.CalculatedSegment seg) {
        if (seg.geometry != null) {
            seg.geometry = (LineString) seg.geometry.reverse();
        }
    }

    private void swapEnds(FlowCalculationService.CalculatedSegment seg) {
        String tmp = seg.segmentStartNodeId;
        seg.segmentStartNodeId = seg.segmentEndNodeId;
        seg.segmentEndNodeId = tmp;
    }

    /**
     * Собирает единый LineString из цепочки сегментов.
     * Все атрибуты (flow, diameter, laying_method) — одинаковые
     * по построению цепочки. Длины и стоимости суммируются.
     * ID ОКС — объединение.
     */
    private FlowCalculationService.CalculatedSegment buildMergedSegment(
            List<FlowCalculationService.CalculatedSegment> chain
    ) {
        FlowCalculationService.CalculatedSegment first = chain.get(0);

        FlowCalculationService.CalculatedSegment merged =
                new FlowCalculationService.CalculatedSegment();
        merged.segmentStartNodeId = first.segmentStartNodeId;
        merged.segmentEndNodeId = chain.get(chain.size() - 1).segmentEndNodeId;
        merged.diameter = first.diameter;
        merged.layingMethod = first.layingMethod;
        merged.kspec = first.kspec;
        merged.flowTph = first.flowTph;

        // Склейка координат без дублирования стыков
        List<Coordinate> coords = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            FlowCalculationService.CalculatedSegment s = chain.get(i);
            Coordinate[] cs = s.geometry.getCoordinates();
            for (int j = 0; j < cs.length; j++) {
                if (i > 0 && j == 0) continue; // пропускаем точку стыка
                coords.add(cs[j]);
            }
            merged.length += s.length;
            merged.cost += s.cost;
        }
        merged.geometry = geometryFactory.createLineString(
                coords.toArray(new Coordinate[0]));
        merged.geometry.setSRID(32637);

        // Объединение OKS-идентификаторов (в порядке первого появления)
        Set<String> oksSet = new LinkedHashSet<>();
        for (FlowCalculationService.CalculatedSegment s : chain) {
            if (s.oksId == null) continue;
            for (String id : s.oksId.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) oksSet.add(trimmed);
            }
        }
        merged.oksId = String.join(",", oksSet);

        return merged;
    }

    /**
     * Строит карту маппинга: net_... → ID камеры (существующей или новой).
     */
    private Map<String, String> buildNodeIdMap(VariantService.Variant variant) {
        Map<String, String> map = new HashMap<>();

        for (RoutingService.Route route : variant.routes) {
            if (route.endNodeId == null) continue;
            if (!route.endNodeId.startsWith("net_")) continue;

            for (TieInService.TieInResult tieIn : variant.cost.tieIns) {
                if (tieIn.oksId != null && tieIn.oksId.contains(route.oksId)) {
                    if (tieIn.useExistingChamber && tieIn.existingChamberId != null) {
                        map.put(route.endNodeId, tieIn.existingChamberId);
                    } else if (tieIn.newChamberId != null) {
                        map.put(route.endNodeId, tieIn.newChamberId);
                    }
                    break;
                }
            }
        }

        return map;
    }

    private void writeNetworkSegment(JsonGenerator gen, String variantId, int index,
                                     FlowCalculationService.CalculatedSegment seg,
                                     Map<String, String> nodeIdMap) throws IOException {
        String startId = mapNodeId(seg.segmentStartNodeId, nodeIdMap);
        String endId = mapNodeId(seg.segmentEndNodeId, nodeIdMap);

        gen.writeStartObject();
        gen.writeStringField("type", "Feature");

        gen.writeObjectFieldStart("geometry");
        writeGeometry(gen, toWgs84(seg.geometry));
        gen.writeEndObject();

        gen.writeObjectFieldStart("properties");
        gen.writeStringField("id", variantId + "_net_" + index);
        gen.writeStringField("object_type", "heat_network");
        gen.writeStringField("variant_id", variantId);
        gen.writeStringField("start_node_id", startId);
        gen.writeStringField("end_node_id", endId);
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

    private String mapNodeId(String originalId, Map<String, String> nodeIdMap) {
        if (originalId == null) return null;
        if (!originalId.startsWith("net_")) return originalId;
        String mapped = nodeIdMap.get(originalId);
        if (mapped != null) return mapped;
        return originalId;
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
        gen.writeStringField("id",
                tieIn.newChamberId != null ? tieIn.newChamberId : variantId + "_chamber_" + index);
        gen.writeStringField("object_type", "heat_chamber");
        gen.writeStringField("variant_id", variantId);
        gen.writeNumberField("diameter", tieIn.newChamberDiameter);
        gen.writeNumberField("cost", round(tieIn.newChamberCost, 2));
        gen.writeEndObject();

        gen.writeEndObject();
    }

    private void writeTechnicalNodes(JsonGenerator gen, String variantId,
                                     VariantService.Variant variant,
                                     Map<String, String> nodeIdMap) throws IOException {
        int index = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (RoutingService.Route route : variant.routes) {
            List<GraphBuilder.Edge> edges = route.edges;
            for (int i = 0; i < edges.size() - 1; i++) {
                GraphBuilder.Edge current = edges.get(i);
                GraphBuilder.Edge next = edges.get(i + 1);

                if (current.layingMethod.equals(next.layingMethod)) continue;

                Coordinate boundaryUtm = current.toCoordinateUtm;
                String key = Math.round(boundaryUtm.x) + "_" + Math.round(boundaryUtm.y);
                if (seen.contains(key)) continue;
                seen.add(key);

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
            if (utmGeom != null && utmGeom.getCoordinate().distance(coordUtm) < 1.0) {
                return true;
            }
        }
        for (var chamber : variant.chambers) {
            Geometry utmGeom = coordinateTransformer.toUtm37n(chamber.getGeometry());
            if (utmGeom != null && utmGeom.getCoordinate().distance(coordUtm) < 1.0) {
                return true;
            }
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
            writeIdWithType(gen, oksId, variant);
        }
        gen.writeEndArray();

        gen.writeEndObject();
        gen.writeEndObject();
    }

    private void writeIdWithType(JsonGenerator gen, String id, VariantService.Variant variant) throws IOException {
        String idType = "string";
        if (variant.allOks != null) {
            for (var oks : variant.allOks) {
                if (id.equals(oks.getId())) {
                    idType = oks.getIdType() != null ? oks.getIdType() : "string";
                    break;
                }
            }
        }

        if ("number".equals(idType)) {
            try {
                gen.writeNumber(Long.parseLong(id));
                return;
            } catch (NumberFormatException e) {
                // fallback
            }
        }
        gen.writeString(id);
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