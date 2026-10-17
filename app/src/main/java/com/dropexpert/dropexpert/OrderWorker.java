package com.dropexpert.dropexpert;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class OrderWorker implements CommandLineRunner {

    public static final String GROUP = "order-workers";

    private final StringRedisTemplate redis;
    private final PurchaseOrderRepository orderRepository;
    private final String consumerName;

    public OrderWorker(StringRedisTemplate redis, PurchaseOrderRepository orderRepository) {
        this.redis = redis;
        this.orderRepository = orderRepository;
        // Each worker needs its own name inside the group. Docker gives every container a unique HOSTNAME.
        this.consumerName = "worker-" + System.getenv().getOrDefault("HOSTNAME", UUID.randomUUID().toString());
    }

    @Override
    public void run(String... args) {
        // Run the loop on its own thread so the app finishes starting up
        new Thread(this::loop, "order-worker").start();
    }

    private void loop() {
        System.out.println(consumerName + " started, waiting for purchases...");
        while (true) {
            try {
                // Ask Redis for up to 50 new tickets for this worker; wait up to 2 seconds if there are none
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(2)),
                        StreamOffset.create(PurchaseService.STREAM_KEY, ReadOffset.lastConsumed()));

                if (records == null) {
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    saveOrder(record.getValue());
                    // Only AFTER the order is saved: tell Redis this ticket is done
                    redis.opsForStream().acknowledge(PurchaseService.STREAM_KEY, GROUP, record.getId());
                }
            } catch (Exception e) {
                if (isMissingGroup(e)) {
                    createGroupIfPossible();
                } else {
                    System.out.println("Worker error: " + e.getMessage());
                }
                sleep(200);
            }
        }
    }

    private void saveOrder(Map<Object, Object> fields) {
        String purchaseId = String.valueOf(fields.get("purchaseId"));
        Long dropId = Long.valueOf(String.valueOf(fields.get("dropId")));
        String userId = String.valueOf(fields.get("userId"));
        try {
            orderRepository.save(new PurchaseOrder(purchaseId, dropId, userId));
        } catch (DataIntegrityViolationException duplicate) {
            // The unique rule on purchase_id refused a second copy:
            // this order was already saved, so there is nothing to do.
            System.out.println("Order " + purchaseId + " already saved, skipping duplicate");
        }
    }

    private boolean isMissingGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }

    private void createGroupIfPossible() {
        if (!Boolean.TRUE.equals(redis.hasKey(PurchaseService.STREAM_KEY))) {
            return; // No purchases yet, so no queue exists. Try again shortly.
        }
        try {
            // "0" = start from the very first ticket in the queue
            redis.opsForStream().createGroup(PurchaseService.STREAM_KEY, ReadOffset.from("0"), GROUP);
            System.out.println("Created consumer group " + GROUP);
        } catch (Exception alreadyExists) {
            // Another worker created it first. That's fine.
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}