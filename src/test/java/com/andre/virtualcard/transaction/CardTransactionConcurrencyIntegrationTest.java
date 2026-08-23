package com.andre.virtualcard.transaction;

import com.andre.virtualcard.card.Card;
import com.andre.virtualcard.card.CardRepository;
import com.andre.virtualcard.idempotency.IdempotencyOperation;
import com.andre.virtualcard.idempotency.IdempotencyRepository;
import com.andre.virtualcard.idempotency.IdempotencyRequest;
import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
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

class CardTransactionConcurrencyIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    private static final int WORKER_TIMEOUT_SECONDS = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardTransactionRepository cardTransactionRepository;

    @Autowired
    private CardTransactionService cardTransactionService;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void twoCompetingSpendsSerializeAndCannotOverspend() throws Exception {
        UUID cardId = createCard("100");
        int workers = 2;

        List<CardMutationResult> results = runConcurrently(workers, cardId,
                () -> new AmountRequest(new BigDecimal("80")), CardTransactionService::spend);

        assertThat(results).hasSize(2);
        long successful = results.stream().filter(CardMutationResult.Successful.class::isInstance).count();
        long declinedInsufficientFunds = results.stream()
                .filter(CardMutationResult.Declined.class::isInstance)
                .map(CardMutationResult.Declined.class::cast)
                .filter(r -> r.reason() == DeclineReason.INSUFFICIENT_FUNDS)
                .count();
        assertThat(successful).isEqualTo(1);
        assertThat(declinedInsufficientFunds).isEqualTo(1);

        assertThat(balanceOf(cardId)).isEqualByComparingTo("20.00");

        List<CardTransaction> spendRows = spendRows(cardId);
        assertThat(spendRows).hasSize(2);
        assertThat(spendRows.stream().filter(t -> t.getStatus() == TransactionStatus.SUCCESSFUL)).hasSize(1);
        List<CardTransaction> declined = spendRows.stream()
                .filter(t -> t.getStatus() == TransactionStatus.DECLINED)
                .toList();
        assertThat(declined).hasSize(1);
        assertThat(declined.get(0).getDeclineReason()).isEqualTo(DeclineReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void twentyConcurrentSpendsOfTenOnBalanceOneHundredProduceTenSuccessAndTenDeclines() throws Exception {
        UUID cardId = createCard("100");
        int workers = 20;

        List<CardMutationResult> results = runConcurrently(workers, cardId,
                () -> new AmountRequest(new BigDecimal("10")), CardTransactionService::spend);

        long successful = results.stream().filter(CardMutationResult.Successful.class::isInstance).count();
        long declinedInsufficientFunds = results.stream()
                .filter(CardMutationResult.Declined.class::isInstance)
                .map(CardMutationResult.Declined.class::cast)
                .filter(r -> r.reason() == DeclineReason.INSUFFICIENT_FUNDS)
                .count();
        assertThat(successful).isEqualTo(10);
        assertThat(declinedInsufficientFunds).isEqualTo(10);

        assertThat(balanceOf(cardId)).isEqualByComparingTo("0.00");

        List<CardTransaction> spendRows = spendRows(cardId);
        assertThat(spendRows).hasSize(workers);
        assertThat(spendRows.stream().filter(t -> t.getStatus() == TransactionStatus.SUCCESSFUL)).hasSize(10);
        assertThat(spendRows.stream().filter(t -> t.getStatus() == TransactionStatus.DECLINED)).hasSize(10);
        assertThat(spendRows.stream()
                .filter(t -> t.getStatus() == TransactionStatus.SUCCESSFUL)
                .map(CardTransaction::getDeclineReason))
                .containsOnly((DeclineReason) null);
        assertThat(spendRows.stream()
                .filter(t -> t.getStatus() == TransactionStatus.DECLINED)
                .map(CardTransaction::getDeclineReason))
                .containsOnly(DeclineReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void concurrentTopUpsDoNotLoseUpdates() throws Exception {
        UUID cardId = createCard("0");
        int workers = 10;

        List<CardMutationResult> results = runConcurrently(workers, cardId,
                () -> new AmountRequest(new BigDecimal("10")), CardTransactionService::topUp);

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(CardMutationResult.Successful.class));

        assertThat(balanceOf(cardId)).isEqualByComparingTo("100.00");

        List<CardTransaction> topUpRows = cardTransactionRepository
                .findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged())
                .getContent()
                .stream()
                .filter(t -> t.getType() == TransactionType.TOP_UP)
                .toList();
        assertThat(topUpRows).hasSize(workers);
        assertThat(topUpRows).allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL));
    }

    private List<CardMutationResult> runConcurrently(
            int workers,
            UUID cardId,
            java.util.function.Supplier<AmountRequest> requestSupplier,
            MutationCall call
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CardMutationResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    // distinct keys: each worker is an independent logical operation
                    String key = UUID.randomUUID().toString();
                    return call.invoke(cardTransactionService, cardId, key, requestSupplier.get());
                }));
            }

            assertThat(ready.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<CardMutationResult> results = new ArrayList<>(workers);
            for (Future<CardMutationResult> future : futures) {
                results.add(future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeySpendsCollapseIntoOneOperation() throws Exception {
        UUID cardId = createCard("100");
        int workers = 20;
        String sharedKey = UUID.randomUUID().toString();

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CardMutationResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return cardTransactionService.spend(
                            cardId, sharedKey, new AmountRequest(new BigDecimal("25")));
                }));
            }
            assertThat(ready.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<CardMutationResult> results = new ArrayList<>(workers);
            for (Future<CardMutationResult> future : futures) {
                results.add(future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(CardMutationResult.Successful.class));
            var transactionIds = results.stream()
                    .map(CardMutationResult.Successful.class::cast)
                    .map(s -> s.transaction().id())
                    .distinct()
                    .toList();
            assertThat(transactionIds).hasSize(1);

            assertThat(balanceOf(cardId)).isEqualByComparingTo("75.00");
            assertThat(spendRows(cardId)).hasSize(1);
            assertThat(spendRows(cardId).get(0).getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentExpiredKeyReclamationCollapsesIntoOneNewOperation() throws Exception {
        UUID cardId = createCard("100");
        String key = UUID.randomUUID().toString();

        // original logical operation
        CardMutationResult original =
                cardTransactionService.spend(cardId, key, new AmountRequest(new BigDecimal("25")));
        assertThat(original).isInstanceOf(CardMutationResult.Successful.class);
        UUID originalTransactionId = ((CardMutationResult.Successful) original).transaction().id();
        assertThat(balanceOf(cardId)).isEqualByComparingTo("75.00");

        // force the idempotency row safely into the past
        IdempotencyRequest row = idempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(
                        IdempotencyOperation.SPEND, cardId, key)
                .orElseThrow();
        jdbcTemplate.update(
                "UPDATE idempotency_request "
                        + "SET created_at = clock_timestamp() - interval '2 hours', "
                        + "expires_at = clock_timestamp() - interval '1 hour' "
                        + "WHERE id = ?",
                row.getId()
        );

        int workers = 20;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CardMutationResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return cardTransactionService.spend(
                            cardId, key, new AmountRequest(new BigDecimal("25")));
                }));
            }
            assertThat(ready.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<CardMutationResult> results = new ArrayList<>(workers);
            for (Future<CardMutationResult> future : futures) {
                results.add(future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            // exactly one reclaimed logical operation, replayed to every other caller
            assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(CardMutationResult.Successful.class));
            var reclaimedTransactionIds = results.stream()
                    .map(CardMutationResult.Successful.class::cast)
                    .map(s -> s.transaction().id())
                    .distinct()
                    .toList();
            assertThat(reclaimedTransactionIds).hasSize(1);
            assertThat(reclaimedTransactionIds.get(0)).isNotEqualTo(originalTransactionId);

            assertThat(balanceOf(cardId)).isEqualByComparingTo("50.00");
            List<CardTransaction> spends = spendRows(cardId);
            assertThat(spends).hasSize(2); // original pre-expiry op + exactly one reclaimed op
            assertThat(spends.stream().map(CardTransaction::getId))
                    .containsExactlyInAnyOrder(originalTransactionId, reclaimedTransactionIds.get(0));
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface MutationCall {
        CardMutationResult invoke(CardTransactionService service, UUID cardId, String key, AmountRequest request);
    }

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

    private BigDecimal balanceOf(UUID cardId) {
        Card card = cardRepository.findById(cardId).orElseThrow();
        return card.getBalance();
    }

    private List<CardTransaction> spendRows(UUID cardId) {
        return cardTransactionRepository.findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged())
                .getContent()
                .stream()
                .filter(t -> t.getType() == TransactionType.SPEND)
                .toList();
    }
}
