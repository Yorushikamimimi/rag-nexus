package com.ragnexus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.ragnexus")
public class RagNexusApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagNexusApplication.class, args);
    }
}
