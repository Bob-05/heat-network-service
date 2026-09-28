package ru.hackathon.heatnetworkservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.hackathon.heatnetworkservice.controller.dto.FileInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "Управление входными и выходными файлами")
public class FileController {

    private static final String UPLOAD_DIR = "uploads/";
    private static final String RESULTS_DIR = "results/";

    @GetMapping
    @Operation(summary = "Список всех файлов",
            description = "Возвращает все входные (uploads) и выходные (results) файлы")
    public ResponseEntity<Map<String, List<FileInfo>>> listAll() {
        Map<String, List<FileInfo>> response = new LinkedHashMap<>();
        response.put("uploads", listFiles(UPLOAD_DIR, "uploads"));
        response.put("results", listFiles(RESULTS_DIR, "results"));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/download/{type}/{fileName}")
    @Operation(summary = "Скачать конкретный файл",
            description = "type = uploads | results")
    public ResponseEntity<Resource> download(
            @Parameter(description = "Тип каталога: uploads или results", example = "results")
            @PathVariable String type,
            @Parameter(description = "Имя файла")
            @PathVariable String fileName) {

        File file = resolveFile(type, fileName);
        if (file == null || !file.exists() || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        MediaType mediaType = fileName.toLowerCase().endsWith(".geojson")
                ? MediaType.parseMediaType("application/geo+json")
                : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getName() + "\"")
                .contentType(mediaType)
                .body(resource);
    }

    @DeleteMapping("/{type}/{fileName}")
    @Operation(summary = "Удалить один файл",
            description = "type = uploads | results")
    public ResponseEntity<Map<String, Object>> deleteOne(
            @Parameter(description = "Тип каталога: uploads или results", example = "uploads")
            @PathVariable String type,
            @Parameter(description = "Имя файла")
            @PathVariable String fileName) {

        Map<String, Object> response = new HashMap<>();
        File file = resolveFile(type, fileName);
        if (file == null || !file.exists()) {
            response.put("status", "NOT_FOUND");
            response.put("file", fileName);
            return ResponseEntity.status(404).body(response);
        }
        boolean deleted = file.delete();
        response.put("status", deleted ? "DELETED" : "ERROR");
        response.put("file", fileName);
        response.put("dir", type);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{type}")
    @Operation(summary = "Очистить все файлы в каталоге",
            description = "type = uploads | results")
    public ResponseEntity<Map<String, Object>> deleteAll(
            @Parameter(description = "Тип каталога: uploads или results", example = "uploads")
            @PathVariable String type) {

        Map<String, Object> response = new HashMap<>();
        File dir = resolveDir(type);
        if (dir == null || !dir.exists()) {
            response.put("status", "NOT_FOUND");
            response.put("dir", type);
            return ResponseEntity.status(404).body(response);
        }

        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.delete()) {
                    count++;
                }
            }
        }
        response.put("status", "CLEARED");
        response.put("deleted", count);
        response.put("dir", type);
        log.info("Очищен каталог {}: удалено {} файлов", type, count);
        return ResponseEntity.ok(response);
    }

    // ---------- helpers ----------

    private List<FileInfo> listFiles(String dirPath, String type) {
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(Paths.get(dirPath))) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> toFileInfo(p, type))
                    .sorted(Comparator.comparingLong(FileInfo::getLastModified).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Ошибка чтения каталога {}: {}", dirPath, e.getMessage());
            return Collections.emptyList();
        }
    }

    private FileInfo toFileInfo(Path path, String type) {
        File file = path.toFile();
        FileInfo info = new FileInfo();
        info.setName(file.getName());
        info.setSize(file.length());
        info.setLastModified(file.lastModified());
        info.setDownloadUrl("/api/v1/files/download/" + type + "/" + file.getName());
        return info;
    }

    private File resolveFile(String type, String fileName) {
        File dir = resolveDir(type);
        if (dir == null || fileName == null) return null;
        // защита от path traversal
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            log.warn("Попытка path traversal: {}", fileName);
            return null;
        }
        return new File(dir, fileName);
    }

    private File resolveDir(String type) {
        if (type == null) return null;
        switch (type) {
            case "uploads": return new File(UPLOAD_DIR);
            case "results": return new File(RESULTS_DIR);
            default: return null;
        }
    }
}