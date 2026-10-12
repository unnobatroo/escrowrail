package com.wise.escrow.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly sweep of expired idempotency replay records. Rows outlive their
 * usefulness once the 5-minute replay window closes; the filter evicts
 * stale rows opportunistically on key collision, but this batch delete is
 * what keeps the table bounded in size. The delete scans an indexed
 * timestamp column so it never blocks live claims.
 */
@Component
public class IdempotencySweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencySweeper.class);

    private final JdbcTemplate jdbc;

    /** Creates a new IdempotencySweeper. */
    public IdempotencySweeper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes records older than 24 hours; logs the row count when nonzero. */
    @Scheduled(cron = "0 0 * * * *")
    public void sweepExpired() {
        int deleted = jdbc.update(
            "DELETE FROM idempotency_records WHERE created_at < NOW() - INTERVAL '24 HOURS'"
        );
        if (deleted > 0) {
            log.info("idempotency sweep removed {} expired records", deleted);
        }
    }
}
