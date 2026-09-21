package com.agenticupi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgenticUpiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticUpiApplication.class, args);
    }
}
