package com.dropexpert.dropexpert;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PurchaseService {

    // Load reserve.lua once, when the app starts
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>();
    static {
        RESERVE_SCRIPT.setLocation(new ClassPathResource("scripts/reserve.lua"));
        RESERVE_SCRIPT.setResultType(Long.class);
    }

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
        // Read, check and subtract in ONE uninterruptible Redis step
        Long reserved = redis.execute(RESERVE_SCRIPT, List.of(inventoryKey(dropId)));

        if (reserved == null || reserved == 0) {
            return new PurchaseResponse("SOLD_OUT", null);
        }

        String purchaseId = UUID.randomUUID().toString();
        orderRepository.save(new PurchaseOrder(purchaseId, dropId, userId));

        return new PurchaseResponse("ACCEPTED", purchaseId);
    }
}