package ru.hackathon.heatnetworkservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hackathon.heatnetworkservice.repository.GeoObjectRepository;
import ru.hackathon.heatnetworkservice.service.GeoJsonReaderService;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "GeoJSON Reader", description = "Чтение тестового GeoJSON")
public class GeoJsonReaderController {

    private final GeoJsonReaderService geoJsonReaderService;
    private final GeoObjectRepository geoObjectRepository;

    @PostMapping("/read-test")
    @Operation(summary = "Прочитать тестовый GeoJSON",
            description = "Читает файл test_input.geojson из resources и сохраняет объекты в БД")
    public ResponseEntity<Map<String, Object>> readTest() {
        try {
            // Очищаем таблицу перед тестом
            geoObjectRepository.deleteAll();

            // Читаем тестовый файл из resources
            File file = new ClassPathResource("test-data/test_input.geojson").getFile();

            // Читаем GeoJSON
            geoJsonReaderService.readGeoJson(file);

            // Возвращаем статистику
            long total = geoObjectRepository.count();
            Map<String, Object> result = new HashMap<>();
            result.put("status", "OK");
            result.put("total_objects", total);
            result.put("file", file.getAbsolutePath());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Ошибка чтения GeoJSON: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}