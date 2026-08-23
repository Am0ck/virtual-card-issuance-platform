package com.andre.virtualcard.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyCleanupService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupService.class);

    private static final String CLEANUP_SQL =
            "DELETE FROM idempotency_request WHERE expires_at <= clock_timestamp()";

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyCleanupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Housekeeping only. Correctness never depends on cleanup having run:
     * expired rows remain atomically reclaimable by the claim UPSERT.
     * Concurrent execution across application instances is safe and idempotent.
     */
    @Scheduled(fixedDelayString = "${idempotency.cleanup-interval-ms:3600000}")
    public int deleteExpiredIdempotencyRequests() {
        int deleted = jdbcTemplate.update(CLEANUP_SQL);
        if (deleted > 0) {
            log.info("idempotency_cleanup deletedCount={}", deleted);
        } else {
            log.debug("idempotency_cleanup deletedCount=0");
        }
        return deleted;
    }
}
