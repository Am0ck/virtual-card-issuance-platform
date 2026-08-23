package com.andre.virtualcard.idempotency;

import com.andre.virtualcard.card.CardRepository;
import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import com.andre.virtualcard.transaction.CardTransaction;
import com.andre.virtualcard.transaction.CardTransactionRepository;
import com.andre.virtualcard.transaction.TransactionStatus;
import com.andre.virtualcard.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyExpiryIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    private static final Pattern TRANSACTION_ID_PATTERN =
            Pattern.compile("\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardTransactionRepository cardTransactionRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private IdempotencyCleanupService cleanupService;

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

    private ResultActions spend(UUID cardId, String amount, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private ResultActions topUp(UUID cardId, String amount, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/cards/" + cardId + "/top-ups")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private UUID firstSpendTransactionId(UUID cardId) {
        List<CardTransaction> spends = spendRows(cardId);
        assertThat(spends).hasSize(1);
        return spends.get(0).getId();
    }

    /** Forces the row safely into the past while respecting expires_at > created_at. */
    private void forceExpired(UUID idempotencyRequestId) {
        jdbcTemplate.update(
                "UPDATE idempotency_request "
                        + "SET created_at = clock_timestamp() - interval '2 hours', "
                        + "expires_at = clock_timestamp() - interval '1 hour' "
                        + "WHERE id = ?",
                idempotencyRequestId
        );
    }

    @Test
    void expiredSuccessfulSpendIsReclaimedAndExecutesAsNewOperation() throws Exception {
        UUID cardId = createCard("100");
        String key = UUID.randomUUID().toString();

        spend(cardId, "25", key).andExpect(status().isCreated());
        UUID originalTransactionId = firstSpendTransactionId(cardId);
        IdempotencyRequest originalRow = idempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(IdempotencyOperation.SPEND, cardId, key)
                .orElseThrow();
        UUID originalSurrogateId = originalRow.getId();

        forceExpired(originalSurrogateId);

        String retryResponse = spend(cardId, "25", key)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID reclaimedTransactionId = extractSingleTransactionId(retryResponse);
        assertThat(reclaimedTransactionId).isNotEqualTo(originalTransactionId); // new logical operation
        assertThat(balanceOf(cardId)).isEqualByComparingTo("50.00");
        assertThat(spendRows(cardId)).hasSize(2);

        // same scope row reused with stable surrogate identity, now bound to the new result
        IdempotencyRequest reclaimedRow = idempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(IdempotencyOperation.SPEND, cardId, key)
                .orElseThrow();
        assertThat(reclaimedRow.getId()).isEqualTo(originalSurrogateId);
        assertThat(reclaimedRow.getResultTransactionId()).isEqualTo(reclaimedTransactionId);

        Boolean unexpiredAgain = jdbcTemplate.queryForObject(
                "SELECT expires_at > clock_timestamp() FROM idempotency_request WHERE id = ?",
                Boolean.class,
                originalSurrogateId
        );
        assertThat(unexpiredAgain).isTrue(); // completion refreshed retention

        // a further retry before expiry replays again: still exactly 2 spend rows
        spend(cardId, "25", key).andExpect(status().isCreated());
        assertThat(spendRows(cardId)).hasSize(2);
    }

    @Test
    void expiredScopeAcceptsChangedPayloadInsteadOfConflicting() throws Exception {
        UUID cardId = createCard("100");
        String key = UUID.randomUUID().toString();

        spend(cardId, "10", key).andExpect(status().isCreated());

        IdempotencyRequest row = idempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(IdempotencyOperation.SPEND, cardId, key)
                .orElseThrow();
        forceExpired(row.getId());

        spend(cardId, "20", key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(20.00))
                .andExpect(jsonPath("$.status").value("SUCCESSFUL")); // NOT 409

        assertThat(balanceOf(cardId)).isEqualByComparingTo("70.00");
        assertThat(spendRows(cardId)).hasSize(2);

        IdempotencyRequest reclaimed = idempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(IdempotencyOperation.SPEND, cardId, key)
                .orElseThrow();
        assertThat(reclaimed.getRequestFingerprint()).isNotEqualTo(row.getRequestFingerprint());
    }

    @Test
    void expiredDurableDeclineCanBeReplacedByNewOperation() throws Exception {
        UUID cardId = createCard("20");
        String key = UUID.randomUUID().toString();

        spend(cardId, "50", key).andExpect(status().isUnprocessableEntity());

        IdempotencyRequest declinedRow = idempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(IdempotencyOperation.SPEND, cardId, key)
                .orElseThrow();
        forceExpired(declinedRow.getId());

        topUp(cardId, "100", UUID.randomUUID().toString()).andExpect(status().isCreated());
        assertThat(balanceOf(cardId)).isEqualByComparingTo("120.00");

        spend(cardId, "50", key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"));

        assertThat(balanceOf(cardId)).isEqualByComparingTo("70.00");

        List<CardTransaction> spendRows = spendRows(cardId);
        assertThat(spendRows).hasSize(2);
        assertThat(spendRows.get(1).getStatus()).isEqualTo(TransactionStatus.DECLINED); // original durable decline
        assertThat(spendRows.get(0).getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL); // reclaimed success
    }

    @Test
    void cleanupDeletesOnlyExpiredRowsAndKeepsBusinessData() throws Exception {
        UUID cardId = createCard("100");
        String expiredKey = UUID.randomUUID().toString();
        String liveKey = UUID.randomUUID().toString();

        spend(cardId, "25", expiredKey).andExpect(status().isCreated());
        spend(cardId, "5", liveKey).andExpect(status().isCreated());

        IdempotencyRequest expiredRow = idempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(IdempotencyOperation.SPEND, cardId, expiredKey)
                .orElseThrow();
        forceExpired(expiredRow.getId());
        UUID associatedTransactionId = expiredRow.getResultTransactionId();

        int deleted = cleanupService.deleteExpiredIdempotencyRequests();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(idempotencyRepository.findById(expiredRow.getId())).isEmpty();
        assertThat(idempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(IdempotencyOperation.SPEND, cardId, liveKey))
                .isPresent(); // safely-unexpired row retained

        // business/history data is untouched by idempotency cleanup (no cascade)
        assertThat(cardRepository.findById(cardId)).isPresent();
        assertThat(cardTransactionRepository.findById(associatedTransactionId)).isPresent();
        assertThat(spendRows(cardId)).hasSize(2);
    }

    private UUID extractSingleTransactionId(String transactionResponse) {
        Matcher matcher = TRANSACTION_ID_PATTERN.matcher(transactionResponse);
        assertThat(matcher.find()).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private BigDecimal balanceOf(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow().getBalance();
    }

    private List<CardTransaction> spendRows(UUID cardId) {
        return cardTransactionRepository.findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged())
                .getContent()
                .stream()
                .filter(t -> t.getType() == TransactionType.SPEND)
                .toList();
    }
}
