package ru.hackathon.heatnetworkservice.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatus {

    public enum State {
        PROCESSING,
        DONE,
        ERROR
    }

    private String taskId;
    private State state;
    private String message;
    private String resultFile;
}