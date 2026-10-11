package com.wise.escrow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EscrowApplication {

    public static void main(String[] args) {
        SpringApplication.run(EscrowApplication.class, args);
    }
}
