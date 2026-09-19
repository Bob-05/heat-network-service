package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.controller.dto.TaskStatus;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    // Хранилище статусов задач (в памяти)
    private final Map<String, TaskStatus> tasks = new ConcurrentHashMap<>();

    /**
     * Создаёт новую задачу и запускает обработку в фоне.
     * Возвращает taskId.
     */
    public String startTask(File inputFile) {
        String taskId = UUID.randomUUID().toString();

        TaskStatus status = new TaskStatus(taskId, TaskStatus.State.PROCESSING,
                "Задача создана, обработка запущена", null);
        tasks.put(taskId, status);

        log.info("Создана задача {} для файла {}", taskId, inputFile.getAbsolutePath());

        // Запускаем обработку в фоне
        processAsync(taskId, inputFile);

        return taskId;
    }

    /**
     * Асинхронная обработка файла.
     */
    @Async
    public void processAsync(String taskId, File inputFile) {
        try {
            log.info("Задача {}: начинаем обработку", taskId);

            // TODO: Здесь будет вызов основной логики:
            // 1. geoJsonReaderService.readGeoJson(inputFile)
            // 2. routingService.buildRoutes()
            // 3. flowCalculationService.calculate()
            // 4. costService.calculate()
            // 5. geoJsonWriterService.writeResult(outputFile, ...)

            // Пока — просто заглушка
            Thread.sleep(3000); // имитация долгой обработки

            // Помечаем как готово
            TaskStatus status = tasks.get(taskId);
            status.setState(TaskStatus.State.DONE);
            status.setMessage("Обработка завершена");
            status.setResultFile("result_" + taskId + ".geojson");

            log.info("Задача {}: обработка завершена", taskId);

        } catch (Exception e) {
            log.error("Задача {}: ошибка обработки: {}", taskId, e.getMessage(), e);

            TaskStatus status = tasks.get(taskId);
            status.setState(TaskStatus.State.ERROR);
            status.setMessage("Ошибка: " + e.getMessage());
        }
    }

    /**
     * Возвращает статус задачи.
     */
    public TaskStatus getStatus(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * Возвращает путь к результату задачи.
     */
    public String getResultFile(String taskId) {
        TaskStatus status = tasks.get(taskId);
        if (status == null || status.getState() != TaskStatus.State.DONE) {
            return null;
        }
        return status.getResultFile();
    }
}