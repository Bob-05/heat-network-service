package ru.hackathon.heatnetworkservice.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Информация о файле в хранилище")
public class FileInfo {

    @Schema(description = "Имя файла", example = "1234567890_test_input.geojson")
    private String name;

    @Schema(description = "Размер файла в байтах", example = "102400")
    private long size;

    @Schema(description = "Время последнего изменения (epoch millis)", example = "1700000000000")
    private long lastModified;

    @Schema(description = "URL для скачивания файла",
            example = "/api/v1/files/download/uploads/1234567890_test_input.geojson")
    private String downloadUrl;
}