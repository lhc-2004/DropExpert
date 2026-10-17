package com.dropexpert.dropexpert;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class OrderWorker implements CommandLineRunner {

    public static final String GROUP = "order-workers";

    // ON CONFLICT DO NOTHING: if this purchaseId is already saved, skip it quietly (idempotency)
    private static final String INSERT_SQL =
            "INSERT INTO orders (purchase_id, drop_id, user_id, created_at) " +
            "VALUES (?, ?, ?, clock_timestamp()) " +
            "ON CONFLICT (purchase_id) DO NOTHING";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String consumerName;

    public OrderWorker(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.consumerName = "worker-" + System.getenv().getOrDefault("HOSTNAME", UUID.randomUUID().toString());
    }

    @Override
    public void run(String... args) {
        new Thread(this::loop, "order-worker").start();
    }

    private void loop() {
        System.out.println(consumerName + " started, waiting for purchases...");
        while (true) {
            try {
                // Grab up to 50 new tickets; wait up to 2 seconds if there are none
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(2)),
                        StreamOffset.create(PurchaseService.STREAM_KEY, ReadOffset.lastConsumed()));

                if (records == null || records.isEmpty()) {
                    continue;
                }

                saveBatch(records);

                // Only AFTER the whole batch is saved: mark all its tickets done in one call
                RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
                redis.opsForStream().acknowledge(PurchaseService.STREAM_KEY, GROUP, ids);
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

    private void saveBatch(List<MapRecord<String, Object, Object>> records) {
        List<Object[]> rows = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> fields = record.getValue();
            rows.add(new Object[]{
                    String.valueOf(fields.get("purchaseId")),
                    Long.valueOf(String.valueOf(fields.get("dropId"))),
                    String.valueOf(fields.get("userId"))
            });
        }

        // One transaction = ONE commit for the whole batch, instead of one commit per order
        int[] results = transaction.execute(status -> jdbc.batchUpdate(INSERT_SQL, rows));

        int skipped = 0;
        if (results != null) {
            for (int r : results) {
                if (r == 0) skipped++;   // 0 rows inserted = this purchaseId was already saved
            }
        }
        if (skipped > 0) {
            System.out.println("Skipped " + skipped + " already-saved order(s)");
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
            return;
        }
        try {
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