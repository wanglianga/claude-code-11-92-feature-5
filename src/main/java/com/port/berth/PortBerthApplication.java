package com.port.berth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PortBerthApplication {
    public static void main(String[] args) {
        SpringApplication.run(PortBerthApplication.class, args);
    }
}
