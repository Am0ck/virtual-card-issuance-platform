package com.andre.virtualcard.card;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CardExpirationService {

    private static final Logger log = LoggerFactory.getLogger(CardExpirationService.class);

    /**
     * Bulk expiration intentionally bypasses Card.close() for DB-side efficiency.
     * The SQL encodes exactly the same legal domain transitions as
     * {@code Card.expireIfPast}: ACTIVE/BLOCKED -&gt; CLOSED, CLOSED remains CLOSED,
     * and only cards whose expiry has passed relative to PostgreSQL
     * {@code clock_timestamp()} (the same authoritative clock used for request-time
     * enforcement) are affected. Balance and transaction history are untouched:
     * expiration is a lifecycle event, not a financial transaction.
     *
     * <p>Safe across multiple application instances: the predicate is idempotent and
     * concurrent executions serialize on row-level locks.</p>
     */
    private static final String EXPIRE_SQL = """
            UPDATE card
            SET status = 'CLOSED'
            WHERE expires_at <= clock_timestamp()
              AND status IN ('ACTIVE', 'BLOCKED')
            """;

    private final JdbcTemplate jdbcTemplate;

    public CardExpirationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${card.expiration.cleanup-interval-ms}")
    public int closeExpiredCards() {
        int expired = jdbcTemplate.update(EXPIRE_SQL);
        if (expired > 0) {
            log.info("card_expiration closedCount={}", expired);
        } else {
            log.debug("card_expiration closedCount=0");
        }
        return expired;
    }
}
