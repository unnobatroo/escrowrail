package com.escrowrail.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly delete of expired idempotency rows — the filter evicts stale rows only on collision, so this batch job bounds the table. Runs on an indexed timestamp; never blocks live claims. */
@Component
public class IdempotencySweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencySweeper.class);

    private final JdbcTemplate jdbc;

    public IdempotencySweeper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes rows older than 24h in bounded batches until clear. */
    @Scheduled(cron = "0 0 * * * *")
    public void sweepExpired() {
        int total = 0;
        int batch;
        do {
            batch = jdbc.update(
                "DELETE FROM idempotency_records WHERE id IN (" +
                    "SELECT id FROM idempotency_records " +
                    "WHERE created_at < NOW() - INTERVAL '24 HOURS' LIMIT 1000)"
            );
            total += batch;
        } while (batch > 0);
        if (total > 0) {
            log.info("idempotency sweep removed {} expired records", total);
        }
    }
}
