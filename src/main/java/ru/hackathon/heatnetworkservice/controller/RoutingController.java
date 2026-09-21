package ru.hackathon.heatnetworkservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.hackathon.heatnetworkservice.model.GeoObject;
import ru.hackathon.heatnetworkservice.repository.GeoObjectRepository;
import ru.hackathon.heatnetworkservice.service.FlowCalculationService;
import ru.hackathon.heatnetworkservice.service.RoutingService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Routing", description = "Тестирование построения маршрутов")
public class RoutingController {

    private final GeoObjectRepository geoObjectRepository;
    private final RoutingService routingService;
    private final FlowCalculationService flowCalculationService;

    @PostMapping("/test-routing")
    @Operation(summary = "Тест маршрутизации",
            description = "Строит маршруты для всех ОКС до ближайших камер и подбирает ДУ")
    public ResponseEntity<Map<String, Object>> testRouting(
            @RequestParam(value = "diameter", defaultValue = "300") Integer diameter
    ) {
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

            // Строим маршруты (diameter — заглушка для проверки препятствий)
            List<RoutingService.Route> routes = routingService.buildRoutes(
                    oksPoints, chambers, obstacles, diameter);

            // Рассчитываем ДУ для каждого маршрута
            List<FlowCalculationService.CalculatedSegment> segments =
                    flowCalculationService.calculateSegments(routes);

            // Формируем ответ
            Map<String, Object> result = new HashMap<>();
            result.put("status", "OK");
            result.put("input_diameter_stub", diameter);
            result.put("oks_count", oksPoints.size());
            result.put("chambers_count", chambers.size());
            result.put("obstacles_count", obstacles.size());
            result.put("routes_built", routes.size());
            result.put("routes_not_built", oksPoints.size() - routes.size());
            result.put("segments_count", segments.size());

            // Детали по маршрутам
            List<Map<String, Object>> routeDetails = new ArrayList<>();
            for (RoutingService.Route route : routes) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("oks_id", route.oksId);
                detail.put("oks_flow_tph", route.oksFlowTph);
                detail.put("chamber_id", route.chamberId);
                detail.put("edges_count", route.edges.size());
                detail.put("total_length_m", Math.round(route.totalLength));

                // Находим ДУ и стоимость по сегментам этого ОКС
                int diameter2 = 0;
                double totalCost = 0;
                for (FlowCalculationService.CalculatedSegment seg : segments) {
                    if (seg.oksId.equals(route.oksId)) {
                        diameter2 = seg.diameter;
                        totalCost += seg.cost;
                    }
                }
                detail.put("diameter", diameter2);
                detail.put("total_cost_rub", Math.round(totalCost));
                routeDetails.add(detail);
            }
            result.put("routes", routeDetails);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Ошибка маршрутизации: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}