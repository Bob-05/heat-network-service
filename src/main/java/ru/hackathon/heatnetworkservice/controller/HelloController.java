package ru.hackathon.heatnetworkservice.controller;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Hello", description = "Тестовый контроллер для проверки API")
public class HelloController {

    @GetMapping("/hello")
    @Operation(summary = "Проверка работы API", description = "Возвращает приветсвие")
    public String hello() {
        return "Hello, heat network service!";
    }
}
