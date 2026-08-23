package com.andre.virtualcard.transaction;

import com.andre.virtualcard.card.Card;
import com.andre.virtualcard.card.CardRepository;
import com.andre.virtualcard.card.CardStatus;
import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CardTransactionApiIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    private static final Pattern TRANSACTION_ID_PATTERN =
            Pattern.compile("\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardTransactionRepository cardTransactionRepository;

    private UUID createCard(String initialBalance) throws Exception {
        String location = mockMvc.perform(post("/api/v1/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\": \"Andre Cassar Mockridge\", \"initialBalance\": "
                                + initialBalance + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private void setCardStatus(UUID cardId, CardStatus target) {
        Card card = cardRepository.findById(cardId).orElseThrow();
        switch (target) {
            case BLOCKED -> card.block();
            case CLOSED -> card.close();
            default -> throw new IllegalArgumentException("Unsupported test status " + target);
        }
        cardRepository.save(card);
    }

    private String balanceOf(UUID cardId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/cards/" + cardId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = Pattern.compile("\"balance\":([0-9]+(?:\\.[0-9]+)?)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private ResultActions spend(UUID cardId, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private ResultActions topUp(UUID cardId, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/cards/" + cardId + "/top-ups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private List<CardTransaction> historyItemsInResponseOrder(UUID cardId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/cards/" + cardId + "/transactions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<CardTransaction> items = new ArrayList<>();
        Matcher matcher = TRANSACTION_ID_PATTERN.matcher(body);
        while (matcher.find()) {
            items.add(cardTransactionRepository.findById(UUID.fromString(matcher.group(1))).orElseThrow());
        }
        return items;
    }

    @Nested
    class Spend {

        @Test
        void successfulSpendDecreasesBalanceAndPersistsSuccessfulTransaction() throws Exception {
            UUID cardId = createCard("100");

            spend(cardId, "25")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.cardId").value(cardId.toString()))
                    .andExpect(jsonPath("$.type").value("SPEND"))
                    .andExpect(jsonPath("$.amount").value(25.00))
                    .andExpect(jsonPath("$.status").value("SUCCESSFUL"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());

            assertThat(balanceOf(cardId)).isEqualTo("75.00");

            List<CardTransaction> items = historyItemsInResponseOrder(cardId);
            assertThat(items).hasSize(2); // initial funding + successful spend
            assertThat(items.get(0).getType()).isEqualTo(TransactionType.SPEND);
            assertThat(items.get(0).getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(items.get(0).getDeclineReason()).isNull();
            assertThat(items.get(1).getType()).isEqualTo(TransactionType.INITIAL_FUNDING);
        }

        @Test
        void insufficientFundsReturns422KeepsBalanceAndPersistsDeclinedTransaction() throws Exception {
            UUID cardId = createCard("20");

            spend(cardId, "50")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").isNotEmpty());

            assertThat(balanceOf(cardId)).isEqualTo("20.00");

            List<CardTransaction> items = historyItemsInResponseOrder(cardId);
            assertThat(items).hasSize(2); // initial funding + declined spend
            assertThat(items.get(0).getType()).isEqualTo(TransactionType.SPEND);
            assertThat(items.get(0).getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(items.get(0).getDeclineReason()).isEqualTo(DeclineReason.INSUFFICIENT_FUNDS);
            assertThat(items.get(1).getType()).isEqualTo(TransactionType.INITIAL_FUNDING);
        }

        @Test
        void blockedCardSpendsReturn409WithPersistedDecline() throws Exception {
            UUID cardId = createCard("100");
            setCardStatus(cardId, CardStatus.BLOCKED);

            spend(cardId, "10")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").isNotEmpty());

            assertThat(balanceOf(cardId)).isEqualTo("100.00");

            List<CardTransaction> items = historyItemsInResponseOrder(cardId);
            assertThat(items).hasSize(2); // initial funding + declined spend
            assertThat(items.get(0).getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(items.get(0).getDeclineReason()).isEqualTo(DeclineReason.CARD_BLOCKED);
            assertThat(items.get(1).getType()).isEqualTo(TransactionType.INITIAL_FUNDING);
        }

        @Test
        void closedCardSpendsReturn409WithPersistedDecline() throws Exception {
            UUID cardId = createCard("100");
            setCardStatus(cardId, CardStatus.CLOSED);

            spend(cardId, "10").andExpect(status().isConflict());

            List<CardTransaction> items = historyItemsInResponseOrder(cardId);
            assertThat(items).hasSize(2); // initial funding + declined spend
            assertThat(items.get(0).getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(items.get(0).getDeclineReason()).isEqualTo(DeclineReason.CARD_CLOSED);
            assertThat(items.get(1).getType()).isEqualTo(TransactionType.INITIAL_FUNDING);
        }
    }

    @Nested
    class TopUp {

        @Test
        void successfulTopUpIncreasesBalanceAndPersistsSuccessfulTransaction() throws Exception {
            UUID cardId = createCard("40");

            topUp(cardId, "10")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.cardId").value(cardId.toString()))
                    .andExpect(jsonPath("$.type").value("TOP_UP"))
                    .andExpect(jsonPath("$.amount").value(10.00))
                    .andExpect(jsonPath("$.status").value("SUCCESSFUL"));

            assertThat(balanceOf(cardId)).isEqualTo("50.00");
        }

        @Test
        void blockedCardTopUpsReturn409WithPersistedDecline() throws Exception {
            UUID cardId = createCard("40");
            setCardStatus(cardId, CardStatus.BLOCKED);

            topUp(cardId, "10").andExpect(status().isConflict());

            assertThat(balanceOf(cardId)).isEqualTo("40.00");

            List<CardTransaction> items = historyItemsInResponseOrder(cardId);
            assertThat(items).hasSize(2); // initial funding + declined top-up
            assertThat(items.get(0).getType()).isEqualTo(TransactionType.TOP_UP);
            assertThat(items.get(0).getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(items.get(0).getDeclineReason()).isEqualTo(DeclineReason.CARD_BLOCKED);
            assertThat(items.get(1).getType()).isEqualTo(TransactionType.INITIAL_FUNDING);
        }

        @Test
        void closedCardTopUpsReturn409WithPersistedDecline() throws Exception {
            UUID cardId = createCard("40");
            setCardStatus(cardId, CardStatus.CLOSED);

            topUp(cardId, "10").andExpect(status().isConflict());

            List<CardTransaction> items = historyItemsInResponseOrder(cardId);
            assertThat(items).hasSize(2); // initial funding + declined top-up
            assertThat(items.get(0).getType()).isEqualTo(TransactionType.TOP_UP);
            assertThat(items.get(0).getDeclineReason()).isEqualTo(DeclineReason.CARD_CLOSED);
            assertThat(items.get(1).getType()).isEqualTo(TransactionType.INITIAL_FUNDING);
        }

        @Test
        void topUpOverflowingNumericRangeIsRejectedWithoutMutationOrPersistence() throws Exception {
            UUID cardId = createCard("99999999999999999.99");

            topUp(cardId, "0.01").andExpect(status().isBadRequest());

            assertThat(balanceOf(cardId)).isEqualTo("99999999999999999.99");

            List<CardTransaction> items = historyItemsInResponseOrder(cardId);
            assertThat(items).hasSize(1); // only the initial funding
            assertThat(items.get(0).getType()).isEqualTo(TransactionType.INITIAL_FUNDING);
        }
    }

    @Nested
    class MutationInput {

        @Test
        void rejectsMissingAmount() throws Exception {
            UUID cardId = createCard("100");

            mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsZeroNegativeAndSubCentAmountsForBothOperations() throws Exception {
            UUID cardId = createCard("100");

            for (String amount : new String[]{"0", "-5.00", "20.001"}) {
                spend(cardId, amount).andExpect(status().isBadRequest());
                topUp(cardId, amount).andExpect(status().isBadRequest());
            }

            assertThat(historyItemsInResponseOrder(cardId))
                    .hasSize(1) // only the initial funding; failed requests persist nothing
                    .allSatisfy(t -> assertThat(t.getType()).isEqualTo(TransactionType.INITIAL_FUNDING));
        }

        @Test
        void rejectsAmountOutsideNumericRange() throws Exception {
            UUID cardId = createCard("100");

            spend(cardId, "100000000000000000.00").andExpect(status().isBadRequest());
        }

        @Test
        void mutationsOnMissingCardReturn404() throws Exception {
            spend(UUID.randomUUID(), "5").andExpect(status().isNotFound());
            topUp(UUID.randomUUID(), "5").andExpect(status().isNotFound());
        }

        @Test
        void mutationsWithMalformedCardUuidReturn400() throws Exception {
            spend("not-a-uuid", "5").andExpect(status().isBadRequest());
            topUp("not-a-uuid", "5").andExpect(status().isBadRequest());
        }
    }

    @Nested
    class History {

        @Test
        void returnsEmptyHistoryForExistingCardWithoutTransactions() throws Exception {
            UUID cardId = createCard("0");

            mockMvc.perform(get("/api/v1/cards/" + cardId + "/transactions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }

        @Test
        void returnsAllAttemptsNewestFirst() throws Exception {
            UUID cardId = createCard("100");
            spend(cardId, "30").andExpect(status().isCreated());
            topUp(cardId, "5").andExpect(status().isCreated());
            spend(cardId, "500").andExpect(status().isUnprocessableEntity()); // declined attempt persists

            List<CardTransaction> items = historyItemsInResponseOrder(cardId);

            assertThat(items).hasSize(4);
            // Newest first: last performed op first, initial funding last.
            // The two middle ops are asserted order-insensitively because equal-microsecond
            // timestamps fall back to random UUID id tie-breaking.
            assertThat(items.get(0).getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(items.get(3).getType()).isEqualTo(TransactionType.INITIAL_FUNDING);
            HashSet<TransactionType> middleTypes = new HashSet<>();
            middleTypes.add(items.get(1).getType());
            middleTypes.add(items.get(2).getType());
            assertThat(middleTypes).containsExactlyInAnyOrder(TransactionType.SPEND, TransactionType.TOP_UP);
        }

        @Test
        void returns404ForMissingCard() throws Exception {
            mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID() + "/transactions"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void rejectsInvalidPagingParameters() throws Exception {
            UUID cardId = createCard("10");

            mockMvc.perform(get("/api/v1/cards/" + cardId + "/transactions?page=-1"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(get("/api/v1/cards/" + cardId + "/transactions?size=0"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(get("/api/v1/cards/" + cardId + "/transactions?size=101"))
                    .andExpect(status().isBadRequest());
        }
    }

    private ResultActions spend(String rawCardId, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/cards/" + rawCardId + "/spends")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private ResultActions topUp(String rawCardId, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/cards/" + rawCardId + "/top-ups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }
}
