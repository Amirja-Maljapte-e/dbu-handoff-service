package com.dbu.handoff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HandoffServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HandoffServiceApplication.class, args);
    }
}
