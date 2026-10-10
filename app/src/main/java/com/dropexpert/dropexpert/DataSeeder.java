package com.dropexpert.dropexpert;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final DropRepository dropRepository;

    public DataSeeder(DropRepository dropRepository) {
        this.dropRepository = dropRepository;
    }

    @Override
    public void run(String... args) {
        dropRepository.save(new Drop(1L, "Limited Sneaker Drop", 1000));
        System.out.println("Seeded drop 1 with 1000 units");
    }
}