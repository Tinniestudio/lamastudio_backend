package com.lamastudio.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LamaStudioApplication {
    public static void main(String[] args) {
        SpringApplication.run(LamaStudioApplication.class, args);
    }
};