package com.dropexpert.dropexpert;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "drops")
public class Drop {

    @Id
    private Long id;

    private String name;

    private int totalUnits;

    private int remainingUnits;

    protected Drop() {
        // JPA needs an empty constructor to build objects from database rows
    }

    public Drop(Long id, String name, int totalUnits) {
        this.id = id;
        this.name = name;
        this.totalUnits = totalUnits;
        this.remainingUnits = totalUnits;
    }

    public void decrementRemaining() {
        this.remainingUnits--;
    }
    
    public Long getId() { return id; }
    public String getName() { return name; }
    public int getTotalUnits() { return totalUnits; }
    public int getRemainingUnits() { return remainingUnits; }
}