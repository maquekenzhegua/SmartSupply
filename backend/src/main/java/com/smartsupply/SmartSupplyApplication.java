package com.smartsupply;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartSupplyApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartSupplyApplication.class, args);
    }
}
