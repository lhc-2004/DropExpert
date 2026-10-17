package com.dropexpert.dropexpert;

import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final DropRepository dropRepository;
    private final StringRedisTemplate redis;

    public DataSeeder(DropRepository dropRepository, StringRedisTemplate redis) {
        this.dropRepository = dropRepository;
        this.redis = redis;
    }

    @Override
    public void run(String... args) {
        if (!dropRepository.existsById(1L)) {
            dropRepository.save(new Drop(1L, "Limited Sneaker Drop", 1000));
            System.out.println("Seeded drop 1 in PostgreSQL");
        }

        // Put 1000 in Redis, but only if no number is there yet
        Boolean created = redis.opsForValue().setIfAbsent(PurchaseService.inventoryKey(1L), "1000");
        if (Boolean.TRUE.equals(created)) {
            System.out.println("Seeded Redis inventory for drop 1: 1000");
        } else {
            System.out.println("Redis inventory for drop 1 already exists, not seeding");
        }
    }
}