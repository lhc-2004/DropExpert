package com.dropexpert.dropexpert;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PurchaseService {

    private final StringRedisTemplate redis;
    private final PurchaseOrderRepository orderRepository;

    public PurchaseService(StringRedisTemplate redis, PurchaseOrderRepository orderRepository) {
        this.redis = redis;
        this.orderRepository = orderRepository;
    }

    public static String inventoryKey(Long dropId) {
        return "drop:" + dropId + ":remaining";
    }

    public PurchaseResponse purchase(Long dropId, String userId) {
        String key = inventoryKey(dropId);

        // 1. READ the shared count from Redis
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return new PurchaseResponse("SOLD_OUT", null);
        }
        long remaining = Long.parseLong(value);

        // 2. CHECK: is anything left?
        if (remaining <= 0) {
            return new PurchaseResponse("SOLD_OUT", null);
        }

        // 3. WRITE: take one unit.
        // DECR by itself is safe, but steps 1-3 together are NOT one step.
        redis.opsForValue().decrement(key);

        String purchaseId = UUID.randomUUID().toString();
        orderRepository.save(new PurchaseOrder(purchaseId, dropId, userId));

        return new PurchaseResponse("ACCEPTED", purchaseId);
    }
}