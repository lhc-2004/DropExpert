package com.dropexpert.dropexpert;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PurchaseService {

    public static final String STREAM_KEY = "purchases";

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>();
    static {
        RESERVE_SCRIPT.setLocation(new ClassPathResource("scripts/reserve.lua"));
        RESERVE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;

    public PurchaseService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String inventoryKey(Long dropId) {
        return "drop:" + dropId + ":remaining";
    }

    public PurchaseResponse purchase(Long dropId, String userId) {
        String purchaseId = UUID.randomUUID().toString();

        // Reserve a unit AND queue the purchase, in one Redis step
        Long reserved = redis.execute(
                RESERVE_SCRIPT,
                List.of(inventoryKey(dropId), STREAM_KEY),
                purchaseId, dropId.toString(), userId);

        if (reserved == null || reserved == 0) {
            return new PurchaseResponse("SOLD_OUT", null);
        }

        // No database work here anymore. A worker will save the order later.
        return new PurchaseResponse("PROCESSING", purchaseId);
    }
}