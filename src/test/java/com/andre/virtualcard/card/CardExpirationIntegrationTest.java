package com.andre.virtualcard.card;

import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import com.andre.virtualcard.transaction.CardTransactionRepository;
import com.andre.virtualcard.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scheduler and request-time expiry behavior. The scheduler interval is overridden
 * to an effectively infinite value so automatic scheduling can never interfere:
 * every assertion here calls closeExpiredCards() explicitly or relies on
 * request-time enforcement only.
 */
@SpringBootTest(properties = "card.expiration.cleanup-interval-ms=9223372036854775807")
class CardExpirationIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    private static final long WORKER_TIMEOUT_SECONDS = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardTransactionRepository cardTransactionRepository;

    @Autowired
    private CardExpirationService cardExpirationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /**
     * Marks the card already expired while respecting the DB invariant
     * chk_card_expiry_after_creation (expires_at > created_at). Both timestamps
     * are placed relative to PostgreSQL clock_timestamp() — not the JVM clock —
     * so the fixture is deterministic regardless of JVM/database clock skew:
     * created_at (2 s ago) &lt; expires_at (1 s ago) &lt; PostgreSQL now.
     */
    private void backdateExpiry(UUID cardId) {
        jdbcTemplate.update("""
                WITH db_time AS (
                    SELECT clock_timestamp() AS now
                )
                UPDATE card
                SET created_at = db_time.now - interval '2 seconds',
                    expires_at = db_time.now - interval '1 second'
                FROM db_time
                WHERE id = ?
                """,
                cardId);
    }

    private CardStatus statusOf(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow().getStatus();
    }

    private BigDecimal balanceOf(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow().getBalance();
    }

    @Test
    void newCardReceivesDeterministicExpiresAtDerivedFromCreatedAtPlusConfiguredLifetime()
            throws Exception {
        UUID cardId = createCard("100");

        Card card = cardRepository.findById(cardId).orElseThrow();

        assertThat(card.getExpiresAt()).isEqualTo(
                card.getCreatedAt().plusSeconds(365L * 24 * 60 * 60));
    }

    @Test
    void schedulerClosesExpiredActiveCardWithoutCreatingTransactionOrTouchingBalance()
            throws Exception {
        UUID cardId = createCard("100");
        backdateExpiry(cardId);
        int transactionsBefore = transactionCountOf(cardId);

        int closed = cardExpirationService.closeExpiredCards();

        assertThat(closed).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(cardId)).isEqualTo(CardStatus.CLOSED);
        // expiration is a lifecycle event, not a financial transaction
        assertThat(transactionCountOf(cardId)).isEqualTo(transactionsBefore);
        assertThat(balanceOf(cardId)).isEqualByComparingTo("100.00");
    }

    @Test
    void schedulerClosesExpiredBlockedCard() throws Exception {
        UUID cardId = createCard("50");
        Card card = cardRepository.findById(cardId).orElseThrow();
        card.block();
        cardRepository.save(card);
        backdateExpiry(cardId);

        cardExpirationService.closeExpiredCards();

        assertThat(statusOf(cardId)).isEqualTo(CardStatus.CLOSED);
    }

    @Test
    void schedulerLeavesNonExpiredAndClosedCardsUnchanged() throws Exception {
        UUID activeCardId = createCard("10");
        UUID blockedCardId = createCard("10");
        UUID closedCardId = createCard("10");
        Card toBlock = cardRepository.findById(blockedCardId).orElseThrow();
        toBlock.block();
        cardRepository.save(toBlock);
        Card closed = cardRepository.findById(closedCardId).orElseThrow();
        closed.close();
        cardRepository.save(closed);

        int closedNow = cardExpirationService.closeExpiredCards();

        assertThat(closedNow).isZero();
        assertThat(statusOf(activeCardId)).isEqualTo(CardStatus.ACTIVE);
        assertThat(statusOf(blockedCardId)).isEqualTo(CardStatus.BLOCKED);
        assertThat(statusOf(closedCardId)).isEqualTo(CardStatus.CLOSED);
    }

    @Test
    void repeatedSchedulerExecutionIsIdempotent() throws Exception {
        UUID cardId = createCard("10");
        backdateExpiry(cardId);

        assertThat(cardExpirationService.closeExpiredCards()).isGreaterThanOrEqualTo(1);
        assertThat(cardExpirationService.closeExpiredCards()).isZero();
        assertThat(statusOf(cardId)).isEqualTo(CardStatus.CLOSED);
    }

    @Test
    void concurrentSchedulerExecutionsProduceCorrectFinalState() throws Exception {
        UUID expiredActive = createCard("10");
        UUID expiredBlocked = createCard("10");
        UUID stillValid = createCard("10");
        backdateExpiry(expiredActive);
        Card blocked = cardRepository.findById(expiredBlocked).orElseThrow();
        blocked.block();
        cardRepository.save(blocked);
        backdateExpiry(expiredBlocked);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> job = () -> {
            ready.countDown();
            if (!start.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start gate timed out");
            }
            return cardExpirationService.closeExpiredCards();
        };
        try {
            Future<Integer> first = executor.submit(job);
            Future<Integer> second = executor.submit(job);
            assertThat(ready.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int firstCount = first.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int secondCount = second.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // each expired row is closed by exactly one of the two executions
            assertThat(firstCount + secondCount).isEqualTo(2);

            assertThat(statusOf(expiredActive)).isEqualTo(CardStatus.CLOSED);
            assertThat(statusOf(expiredBlocked)).isEqualTo(CardStatus.CLOSED);
            assertThat(statusOf(stillValid)).isEqualTo(CardStatus.ACTIVE);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void historyRemainsReadableAfterExpiry() throws Exception {
        UUID cardId = createCard("100");
        mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 25}"))
                .andExpect(status().isCreated());
        backdateExpiry(cardId);
        cardExpirationService.closeExpiredCards();

        // GET card remains available after expiry (may briefly reflect persisted status
        // until materialization; here the scheduler already ran so CLOSED is persisted)
        mockMvc.perform(get("/api/v1/cards/" + cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.balance").value(75.00));

        mockMvc.perform(get("/api/v1/cards/" + cardId + "/transactions"))
                .andExpect(status().isOk());

        List<com.andre.virtualcard.transaction.CardTransaction> history = cardTransactionRepository
                .findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged()).getContent();
        assertThat(history).hasSize(2); // initial funding + spend: nothing rewritten or deleted
        assertThat(balanceOf(cardId)).isEqualByComparingTo("75.00");
    }

    @Test
    void spendAfterExpiryDeclinesCardClosedBeforeAnySchedulerRun() throws Exception {
        UUID cardId = createCard("100");
        backdateExpiry(cardId);

        String body = mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 25}"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        // request-time enforcement materialized the expiry without any scheduler run
        assertThat(statusOf(cardId)).isEqualTo(CardStatus.CLOSED);
        assertThat(body).contains("CARD_CLOSED");
        assertThat(balanceOf(cardId)).isEqualByComparingTo("100.00");
        List<com.andre.virtualcard.transaction.CardTransaction> spends =
                rowsOfType(cardId, TransactionType.SPEND);
        assertThat(spends).hasSize(1);
        assertThat(spends.get(0).getStatus())
                .isEqualTo(com.andre.virtualcard.transaction.TransactionStatus.DECLINED);
        assertThat(spends.get(0).getDeclineReason())
                .isEqualTo(com.andre.virtualcard.transaction.DeclineReason.CARD_CLOSED);
    }

    @Test
    void topUpAfterExpiryDeclinesCardClosedBeforeAnySchedulerRun() throws Exception {
        UUID cardId = createCard("0");
        backdateExpiry(cardId);

        mockMvc.perform(post("/api/v1/cards/" + cardId + "/top-ups")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 25}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARD_CLOSED"));

        assertThat(statusOf(cardId)).isEqualTo(CardStatus.CLOSED);
        assertThat(balanceOf(cardId)).isEqualByComparingTo("0.00");
        assertThat(rowsOfType(cardId, TransactionType.TOP_UP)).hasSize(1);
    }

    @Test
    void preExpirySuccessStillReplaysSuccessfullyAfterLaterExpiry() throws Exception {
        UUID cardId = createCard("100");
        String key = UUID.randomUUID().toString();
        String originalBody = mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 25}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String originalTxnId = extractTxnId(originalBody);

        // card expires AFTER the successful operation was committed
        backdateExpiry(cardId);

        // same key replays the ORIGINAL result; no re-evaluation against CLOSED state
        mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 25.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(originalTxnId))
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"));

        assertThat(balanceOf(cardId)).isEqualByComparingTo("75.00");
        assertThat(rowsOfType(cardId, TransactionType.SPEND)).hasSize(1);
    }

    @Test
    void newKeyAfterExpiryDeclinesDurably() throws Exception {
        UUID cardId = createCard("100");
        String oldKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                        .header("Idempotency-Key", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 25}"))
                .andExpect(status().isCreated());
        backdateExpiry(cardId);

        // brand-new key evaluates current state and declines durably
        mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARD_CLOSED"));

        assertThat(balanceOf(cardId)).isEqualByComparingTo("75.00");
    }

    @Test
    void blockedExpiredCardBecomesClosedThroughRequestTimeEnforcementToo() throws Exception {
        UUID cardId = createCard("100");
        Card card = cardRepository.findById(cardId).orElseThrow();
        card.block();
        cardRepository.save(card);
        backdateExpiry(cardId);

        // no scheduler involved: mutation request enforces expiry immediately
        mockMvc.perform(post("/api/v1/cards/" + cardId + "/top-ups")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARD_CLOSED"));

        assertThat(statusOf(cardId)).isEqualTo(CardStatus.CLOSED);
    }

    private int transactionCountOf(UUID cardId) {
        return cardTransactionRepository
                .findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged())
                .getContent().size();
    }

    private List<com.andre.virtualcard.transaction.CardTransaction> rowsOfType(
            UUID cardId, TransactionType type) {
        return cardTransactionRepository
                .findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged())
                .getContent().stream()
                .filter(t -> t.getType() == type)
                .toList();
    }

    private static String extractTxnId(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"id\":\"([0-9a-f-]{36})\"").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
