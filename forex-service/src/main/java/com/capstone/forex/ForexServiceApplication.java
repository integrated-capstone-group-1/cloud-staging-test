package com.capstone.forex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.capstone.forex", "com.capstone.common"})
@EnableScheduling
public class ForexServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForexServiceApplication.class, args);
    }
}
