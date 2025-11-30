package com.example.mafiagame;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MafiaGameApplication {

    public static void main(String[] args) {
        SpringApplication.run(MafiaGameApplication.class, args);
    }
}
