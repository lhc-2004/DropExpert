package com.dropexpert.dropexpert;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String purchaseId;

    @Column(nullable = false)
    private Long dropId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private Instant createdAt;

    protected PurchaseOrder() {
    }

    public PurchaseOrder(String purchaseId, Long dropId, String userId) {
        this.purchaseId = purchaseId;
        this.dropId = dropId;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getPurchaseId() { return purchaseId; }
    public Long getDropId() { return dropId; }
    public String getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
}