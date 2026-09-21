package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.controller.dto.TaskStatus;
import ru.hackathon.heatnetworkservice.model.GeoObject;
import ru.hackathon.heatnetworkservice.repository.GeoObjectRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private static final String RESULTS_DIR = "results/";

    private final Map<String, TaskStatus> tasks = new ConcurrentHashMap<>();

    private final GeoJsonReaderService geoJsonReaderService;
    private final GeoObjectRepository geoObjectRepository;
    private final VariantService variantService;
    private final GeoJsonWriterService geoJsonWriterService;

    /**
     * Создаёт новую задачу и запускает обработку в фоне.
     */
    public String startTask(File inputFile) {
        String taskId = UUID.randomUUID().toString();

        TaskStatus status = new TaskStatus(taskId, TaskStatus.State.PROCESSING,
                "Задача создана, обработка запущена", null);
        tasks.put(taskId, status);

        log.info("Создана задача {} для файла {}", taskId, inputFile.getAbsolutePath());

        processAsync(taskId, inputFile);

        return taskId;
    }

    /**
     * Асинхронная обработка файла — полный пайплайн.
     */
    @Async
    public void processAsync(String taskId, File inputFile) {
        try {
            log.info("Задача {}: начинаем обработку", taskId);

            // 1. Очищаем БД и читаем входной GeoJSON
            geoObjectRepository.deleteAll();
            geoJsonReaderService.readGeoJson(inputFile);

            // 2. Загружаем объекты и разделяем по типам
            List<GeoObject> allObjects = geoObjectRepository.findAll();
            log.info("Задача {}: загружено объектов из БД: {}", taskId, allObjects.size());

            List<GeoObject> oksPoints = new ArrayList<>();
            List<GeoObject> chambers = new ArrayList<>();
            List<GeoObject> obstacles = new ArrayList<>();
            List<GeoObject> existingNetworks = new ArrayList<>();

            for (GeoObject obj : allObjects) {
                String type = obj.getObjectType();
                if ("oks_connection_point".equals(type)) {
                    oksPoints.add(obj);
                } else if ("heat_chamber".equals(type)) {
                    chambers.add(obj);
                } else if ("restriction".equals(type)) {
                    obstacles.add(obj);
                } else if ("heat_network".equals(type)) {
                    existingNetworks.add(obj);
                }
            }

            log.info("Задача {}: ОКС={}, камеры={}, препятствия={}, сети={}",
                    taskId, oksPoints.size(), chambers.size(),
                    obstacles.size(), existingNetworks.size());

            // 3. Формируем варианты
            log.info("Задача {}: запускаем VariantService", taskId);
            long startTime = System.currentTimeMillis();
            List<VariantService.Variant> variants = variantService.buildVariants(
                    oksPoints, chambers, obstacles, existingNetworks);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Задача {}: вариантов={}, время={} мс", taskId, variants.size(), elapsed);

            // 4. Записываем результат в GeoJSON
            File resultsDir = new File(RESULTS_DIR);
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
            }
            File outputFile = new File(resultsDir, "result_" + taskId + ".geojson");

            log.info("Задача {}: записываем GeoJSON в {}", taskId, outputFile.getAbsolutePath());
            geoJsonWriterService.writeVariants(outputFile, variants);

            // 5. Обновляем статус
            TaskStatus status = tasks.get(taskId);
            status.setState(TaskStatus.State.DONE);
            status.setMessage("Обработка завершена");
            status.setResultFile(outputFile.getAbsolutePath());

            log.info("Задача {}: обработка завершена успешно", taskId);

        } catch (Exception e) {
            log.error("Задача {}: ошибка обработки: {}", taskId, e.getMessage(), e);

            TaskStatus status = tasks.get(taskId);
            if (status != null) {
                status.setState(TaskStatus.State.ERROR);
                status.setMessage("Ошибка: " + e.getMessage());
            }
        }
    }

    public TaskStatus getStatus(String taskId) {
        return tasks.get(taskId);
    }

    public String getResultFile(String taskId) {
        TaskStatus status = tasks.get(taskId);
        if (status == null || status.getState() != TaskStatus.State.DONE) {
            return null;
        }
        return status.getResultFile();
    }
}