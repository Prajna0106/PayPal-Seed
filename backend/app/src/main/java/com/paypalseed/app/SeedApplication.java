package com.paypalseed.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.paypalseed")
public class SeedApplication {
    public static void main(String[] args) {
        String role = System.getenv().getOrDefault("SEED_ROLE", "api");
        System.out.println("Starting PayPal Seed in role: " + role);
        SpringApplication.run(SeedApplication.class, args);
    }
}
