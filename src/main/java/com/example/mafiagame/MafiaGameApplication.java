package com.example.mafiagame;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.mafiagame.user.service.UserService;

@EnableScheduling
@SpringBootApplication
public class MafiaGameApplication {

    public static void main(String[] args) {
        SpringApplication.run(MafiaGameApplication.class, args);
    }

    @Bean
    public CommandLineRunner initDummyData(UserService userService) {
        return args -> {
            userService.createDummyUsers(100); // dummy1 ~ dummy100 자동 생성
            System.out.println("✅ 테스트용 더미 유저 100명 생성 완료!");
        };
    }
}
