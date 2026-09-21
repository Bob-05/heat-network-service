package ru.hackathon.heatnetworkservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hackathon.heatnetworkservice.model.GeoObject;
import ru.hackathon.heatnetworkservice.repository.GeoObjectRepository;
import ru.hackathon.heatnetworkservice.service.FlowCalculationService;
import ru.hackathon.heatnetworkservice.service.RoutingService;
import ru.hackathon.heatnetworkservice.service.VariantService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Routing", description = "Тестирование построения маршрутов и вариантов")
public class RoutingController {

    private final GeoObjectRepository geoObjectRepository;
    private final VariantService variantService;

    @PostMapping("/test-routing")
    @Operation(summary = "Тест формирования вариантов",
            description = "Строит до 3 вариантов подключения ОКС и ранжирует их по score")
    public ResponseEntity<Map<String, Object>> testRouting() {
        try {
            // Загружаем объекты из БД
            List<GeoObject> allObjects = geoObjectRepository.findAll();
            log.info("Загружено объектов из БД: {}", allObjects.size());

            // Разделяем по типам
            List<GeoObject> oksPoints = new ArrayList<>();
            List<GeoObject> chambers = new ArrayList<>();
            List<GeoObject> obstacles = new ArrayList<>();

            for (GeoObject obj : allObjects) {
                String type = obj.getObjectType();
                if ("oks_connection_point".equals(type)) {
                    oksPoints.add(obj);
                } else if ("heat_chamber".equals(type)) {
                    chambers.add(obj);
                } else if ("restriction".equals(type)) {
                    obstacles.add(obj);
                }
            }

            log.info("ОКС: {}, Камеры: {}, Препятствия: {}",
                    oksPoints.size(), chambers.size(), obstacles.size());

            // Формируем варианты
            long startTime = System.currentTimeMillis();
            List<VariantService.Variant> variants = variantService.buildVariants(
                    oksPoints, chambers, obstacles);
            long elapsed = System.currentTimeMillis() - startTime;

            // Формируем ответ
            Map<String, Object> result = new HashMap<>();
            result.put("status", "OK");
            result.put("oks_count", oksPoints.size());
            result.put("chambers_count", chambers.size());
            result.put("obstacles_count", obstacles.size());
            result.put("variants_count", variants.size());
            result.put("elapsed_ms", elapsed);

            // Детали по вариантам
            List<Map<String, Object>> variantDetails = new ArrayList<>();
            for (VariantService.Variant v : variants) {
                Map<String, Object> vd = new HashMap<>();
                vd.put("variant_id", v.variantId);
                vd.put("rank", v.rank);
                vd.put("weight_type", v.weightType.name());

                // Маршруты
                List<Map<String, Object>> routeDetails = new ArrayList<>();
                for (RoutingService.Route route : v.routes) {
                    Map<String, Object> rd = new HashMap<>();
                    rd.put("oks_id", route.oksId);
                    rd.put("oks_flow_tph", route.oksFlowTph);
                    rd.put("chamber_id", route.chamberId);
                    rd.put("edges_count", route.edges.size());
                    rd.put("total_length_m", Math.round(route.totalLength));

                    // ДУ этого маршрута
                    int diameter = 0;
                    double routeCost = 0;
                    for (FlowCalculationService.CalculatedSegment seg : v.segments) {
                        if (seg.oksId.equals(route.oksId)) {
                            diameter = seg.diameter;
                            routeCost += seg.cost;
                        }
                    }
                    rd.put("diameter", diameter);
                    rd.put("total_cost_rub", Math.round(routeCost));
                    routeDetails.add(rd);
                }
                vd.put("routes", routeDetails);

                // Стоимость
                Map<String, Object> costMap = new HashMap<>();
                costMap.put("construction_cost", Math.round(v.cost.constructionCost));
                costMap.put("chamber_construction_cost", Math.round(v.cost.chamberConstructionCost));
                costMap.put("existing_chamber_tie_in_count", v.cost.existingChamberTieInCount);
                costMap.put("existing_chamber_tie_in_cost", Math.round(v.cost.existingChamberTieInCost));
                costMap.put("unconnected_penalty", Math.round(v.cost.unconnectedPenalty));
                costMap.put("calculated_cost", Math.round(v.cost.calculatedCost));
                costMap.put("new_network_length", Math.round(v.cost.newNetworkLength));
                costMap.put("score", Math.round(v.cost.score * 10000.0) / 10000.0);
                costMap.put("unconnected_oks_ids", v.cost.unconnectedOksIds);
                vd.put("cost", costMap);

                variantDetails.add(vd);
            }
            result.put("variants", variantDetails);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Ошибка формирования вариантов: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}