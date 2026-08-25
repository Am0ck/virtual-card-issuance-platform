package com.andre.virtualcard.card;

import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import com.andre.virtualcard.transaction.CardTransactionRepository;
import com.andre.virtualcard.transaction.DeclineReason;
import com.andre.virtualcard.transaction.TransactionStatus;
import com.andre.virtualcard.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Critical request-time enforcement race: the scheduled expiration job is
 * disabled for this context (effectively infinite interval), so ONLY the
 * request-time DB-clock check can produce the outcome.
 */
@SpringBootTest(properties = "card.expiration.cleanup-interval-ms=9223372036854775807")
class CardExpirationLockWaitIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    private static final long WORKER_TIMEOUT_SECONDS = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardTransactionRepository cardTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID createCard(String initialBalance) throws Exception {
        String location = mockMvc.perform(post("/api/v1/cards")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\": \"Jane Doe\", \"initialBalance\": "
                                + initialBalance + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private boolean dbTimeHasPassed(UUID cardId) {
        Boolean passed = jdbcTemplate.queryForObject(
                "SELECT clock_timestamp() >= (SELECT expires_at FROM card WHERE id = ?)",
                Boolean.class,
                cardId);
        return Boolean.TRUE.equals(passed);
    }

    private void awaitDbTimePastExpiryBoundary(UUID cardId, String phase) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(WORKER_TIMEOUT_SECONDS);
        while (!dbTimeHasPassed(cardId)) {
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalStateException(
                        "DB clock did not pass expiry boundary within timeout during " + phase);
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    @Test
    void spendClaimingKeyBeforeExpiryButAcquiringLockAfterBoundaryDeclinesDurably()
            throws Exception {
        UUID cardId = createCard("100");

        // near-future expiry boundary: crossing it is coordinated via DB time polling,
        // never unbounded sleeps
        jdbcTemplate.update(
                "UPDATE card SET expires_at = clock_timestamp() + interval '2 seconds' WHERE id = ?",
                cardId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 1) hold the card row lock in a separate transaction
            CountDownLatch lockHeld = new CountDownLatch(1);
            CountDownLatch releaseLock = new CountDownLatch(1);
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            Future<?> lockHolder = executor.submit(() -> template.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT id FROM card WHERE id = ? FOR UPDATE",
                        UUID.class,
                        cardId);
                lockHeld.countDown();
                try {
                    if (!releaseLock.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release gate timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                // rollback: nothing to persist; the lock is what matters
                status.setRollbackOnly();
            }));

            assertThat(lockHeld.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // 2) submit the spend: it claims key A, then blocks waiting for the card lock
            String keyA = UUID.randomUUID().toString();
            Future<String> spendResponse = executor.submit(() -> mockMvc
                    .perform(post("/api/v1/cards/" + cardId + "/spends")
                            .header("Idempotency-Key", keyA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 25}"))
                    .andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString());

            awaitRequestBlockedOnLock();
            awaitDbTimePastExpiryBoundary(cardId, "expiry boundary crossing while lock held");

            // 3) release the lock: the request acquires it AFTER the expiry boundary
            releaseLock.countDown();
            String body = spendResponse.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            lockHolder.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(body).contains("CARD_CLOSED");
            assertThat(cardRepository.findById(cardId).orElseThrow().getStatus())
                    .isEqualTo(CardStatus.CLOSED);
            assertThat(cardRepository.findById(cardId).orElseThrow().getBalance())
                    .isEqualByComparingTo("100.00"); // no balance mutation

            List<com.andre.virtualcard.transaction.CardTransaction> spends = cardTransactionRepository
                    .findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged()).getContent()
                    .stream().filter(t -> t.getType() == TransactionType.SPEND).toList();
            assertThat(spends).hasSize(1); // no funding row is a SPEND; exactly one declined attempt
            var declined = spends.get(0);
            assertThat(declined.getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(declined.getDeclineReason()).isEqualTo(DeclineReason.CARD_CLOSED);

            // 4) key A was completed against the declined transaction and replays it
            var claim = jdbcTemplate.queryForMap(
                    "SELECT result_transaction_id FROM idempotency_request "
                            + "WHERE operation_type = 'SPEND' AND resource_id = ? AND idempotency_key = ?",
                    cardId, keyA);
            assertThat(claim.get("result_transaction_id")).isEqualTo(declined.getId());

            mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                            .header("Idempotency-Key", keyA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 25}"))
                    .andExpect(status().isConflict());
            assertThat(rowsOfType(cardId)).hasSize(1); // replay created no new attempt
        } finally {
            executor.shutdownNow();
        }
    }

    private List<com.andre.virtualcard.transaction.CardTransaction> rowsOfType(UUID cardId) {
        return cardTransactionRepository
                .findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged()).getContent()
                .stream().filter(t -> t.getType() == TransactionType.SPEND).toList();
    }

    /**
     * Boundedly waits until the spend request is visibly blocked waiting for a lock.
     * The idempotency claim row itself is uncommitted at that point (same transaction
     * as the business work), so it is not observable from another connection; but the
     * code order inside mutate() guarantees the claim executed before the card-lock
     * wait, so an observed lock-wait proves claim-then-blocked.
     */
    private void awaitRequestBlockedOnLock() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(WORKER_TIMEOUT_SECONDS);
        while (!isAnySessionWaitingOnALock()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalStateException("no lock-waiting session appeared within timeout");
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private boolean isAnySessionWaitingOnALock() {
        Integer waiting = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() "
                        + "AND state = 'active' AND wait_event_type = 'Lock'",
                Integer.class);
        return waiting != null && waiting >= 1;
    }
}
