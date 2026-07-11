package com.tinniestudio.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MediaWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaWorkerApplication.class, args);
    }
}
