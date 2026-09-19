package ru.hackathon.heatnetworkservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class HeatNetworkServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(HeatNetworkServiceApplication.class, args);
	}

}
