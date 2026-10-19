package com.dropexpert.dropexpert;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class OrderWorker implements CommandLineRunner {

    public static final String GROUP = "order-workers";

    // A ticket pending this long is treated as abandoned by a crashed worker
    private static final Duration STUCK_AFTER = Duration.ofSeconds(10);
    // How often each worker looks for abandoned tickets
    private static final long RECOVERY_CHECK_EVERY_MS = 5000;

    // ON CONFLICT DO NOTHING: if this purchaseId is already saved, skip it quietly (idempotency)
    private static final String INSERT_SQL =
            "INSERT INTO orders (purchase_id, drop_id, user_id, created_at) " +
            "VALUES (?, ?, ?, clock_timestamp()) " +
            "ON CONFLICT (purchase_id) DO NOTHING";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String consumerName;

    private long lastRecoveryCheck = 0;

    public OrderWorker(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.transaction = transaction;
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
                // Every few seconds: rescue tickets abandoned by crashed workers
                if (System.currentTimeMillis() - lastRecoveryCheck >= RECOVERY_CHECK_EVERY_MS) {
                    lastRecoveryCheck = System.currentTimeMillis();
                    recoverStuckTickets();
                }

                // Normal work: grab up to 50 new tickets; wait up to 2 seconds if there are none
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(2)),
                        StreamOffset.create(PurchaseService.STREAM_KEY, ReadOffset.lastConsumed()));

                if (records == null || records.isEmpty()) {
                    continue;
                }
                processBatch(records);
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

    private void processBatch(List<MapRecord<String, Object, Object>> records) {
        saveBatch(records);

        // Only AFTER the whole batch is saved: mark all its tickets done in one call
        RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
        redis.opsForStream().acknowledge(PurchaseService.STREAM_KEY, GROUP, ids);
    }

    private void recoverStuckTickets() {
        // Ask Redis for tickets that were handed out but never marked done
        PendingMessages pending = redis.opsForStream().pending(
                PurchaseService.STREAM_KEY, GROUP, Range.unbounded(), 100);

        List<RecordId> stuckIds = new ArrayList<>();
        Set<String> owners = new LinkedHashSet<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(STUCK_AFTER) >= 0) {
                stuckIds.add(message.getId());
                owners.add(message.getConsumerName());
            }
        }
        if (stuckIds.isEmpty()) {
            return;
        }

        // Take them over. Redis only hands over tickets that are STILL idle for 10s,
        // so if two workers try at the same moment, each ticket goes to only one of them.
        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                PurchaseService.STREAM_KEY, GROUP, consumerName, STUCK_AFTER,
                stuckIds.toArray(new RecordId[0]));

        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        System.out.println("Recovered " + claimed.size() + " stuck ticket(s) from " + owners);
        processBatch(claimed);
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