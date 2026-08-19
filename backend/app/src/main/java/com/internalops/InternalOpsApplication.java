package com.internalops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class InternalOpsApplication {
    public static void main(String[] args) { SpringApplication.run(InternalOpsApplication.class, args); }
}
