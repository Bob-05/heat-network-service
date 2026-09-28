package ru.hackathon.heatnetworkservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.hackathon.heatnetworkservice.controller.dto.TaskStatus;
import ru.hackathon.heatnetworkservice.service.TaskService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Task", description = "Обработка GeoJSON-файлов")
public class TaskController {

    private final TaskService taskService;

    private static final String UPLOAD_DIR = "uploads/";

    @PostMapping(value = "/solve", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить GeoJSON-файл",
            description = "Принимает GeoJSON-файл, запускает обработку в фоне, возвращает taskId")
    public ResponseEntity<Map<String, String>> solve(@RequestParam("file") MultipartFile file) {
        try {
            File uploadDir = new File(UPLOAD_DIR);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(UPLOAD_DIR, fileName);
            Files.write(filePath, file.getBytes());

            log.info("Файл сохранён: {}", filePath.toAbsolutePath());

            String taskId = taskService.startTask(filePath.toFile());

            Map<String, String> response = new HashMap<>();
            response.put("taskId", taskId);
            response.put("status", "PROCESSING");
            response.put("message", "Файл принят, обработка запущена");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Ошибка загрузки файла: {}", e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Ошибка загрузки: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @GetMapping("/status/{taskId}")
    @Operation(summary = "Проверить статус задачи",
            description = "Возвращает текущий статус обработки: PROCESSING, DONE, ERROR")
    public ResponseEntity<TaskStatus> getStatus(@PathVariable String taskId) {
        TaskStatus status = taskService.getStatus(taskId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/result/{taskId}")
    @Operation(summary = "Скачать результат",
            description = "Возвращает готовый GeoJSON-файл с результатом обработки")
    public ResponseEntity<Resource> getResult(@PathVariable String taskId) {
        String resultFile = taskService.getResultFile(taskId);
        if (resultFile == null) {
            return ResponseEntity.notFound().build();
        }

        File file = new File(resultFile);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(resource);
    }
}