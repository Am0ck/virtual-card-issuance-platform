package com.andre.virtualcard.common.error;

import com.andre.virtualcard.card.CardRepository;
import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import com.andre.virtualcard.transaction.CardTransactionRepository;
import com.andre.virtualcard.transaction.TransactionType;import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiErrorContractIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardTransactionRepository cardTransactionRepository;

    private UUID createCard(String initialBalance) throws Exception {
        String location = mockMvc.perform(post("/api/v1/cards")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\": \"Andre Cassar Mockridge\", \"initialBalance\": "
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

    private void assertProblem(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Nested
    class BadRequest {

        @Test
        void missingIdempotencyKeyReturnsProblemDetail400() throws Exception {
            assertProblem(mockMvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cardholderName\": \"Andre\", \"initialBalance\": 10}")),
                    400, "INVALID_REQUEST");
        }

        @Test
        void malformedUuidReturnsProblemDetail400() throws Exception {
            assertProblem(mockMvc.perform(get("/api/v1/cards/not-a-uuid")),
                    400, "INVALID_REQUEST");
        }

        @Test
        void invalidAmountReturnsProblemDetail400() throws Exception {
            UUID cardId = createCard("100");

            assertProblem(spend(cardId, "-5.00", UUID.randomUUID().toString()),
                    400, "INVALID_REQUEST");
        }

        @Test
        void malformedJsonReturnsProblemDetail400() throws Exception {
            assertProblem(mockMvc.perform(post("/api/v1/cards")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not-json")),
                    400, "INVALID_REQUEST");
        }

        @Test
        void problemDetailRequestIdMatchesResponseHeaderAndParsesAsUuid() throws Exception {
            var result = mockMvc.perform(get("/api/v1/cards/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            String headerValue = result.getResponse().getHeader("X-Request-Id");
            String bodyRequestId = result.getResponse().getContentAsString()
                    .replaceAll(".*\"requestId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

            assertThat(headerValue).isNotBlank();
            assertThat(UUID.fromString(headerValue)).isNotNull(); // parses as UUID
            assertThat(bodyRequestId).isEqualTo(headerValue);
        }
    }

    @Nested
    class NotFound {

        @Test
        void missingCardReturnsCardNotFound() throws Exception {
            assertProblem(mockMvc.perform(get("/api/v1/cards/" + UUID.randomUUID())),
                    404, "CARD_NOT_FOUND");
        }

        @Test
        void mutationOnMissingCardReturnsCardNotFound() throws Exception {
            assertProblem(spend(UUID.randomUUID(), "5", UUID.randomUUID().toString()),
                    404, "CARD_NOT_FOUND");
        }
    }

    @Nested
    class Conflict {

        @Test
        void blockedCardSpendReturnsCardBlocked() throws Exception {
            UUID cardId = createCard("100");
            var card = cardRepository.findById(cardId).orElseThrow();
            card.block();
            cardRepository.save(card);

            assertProblem(spend(cardId, "10", UUID.randomUUID().toString()),
                    409, "CARD_BLOCKED");
        }

        @Test
        void closedCardSpendReturnsCardClosed() throws Exception {
            UUID cardId = createCard("100");
            var card = cardRepository.findById(cardId).orElseThrow();
            card.close();
            cardRepository.save(card);

            assertProblem(spend(cardId, "10", UUID.randomUUID().toString()),
                    409, "CARD_CLOSED");
        }

        @Test
        void changedPayloadUnderSameKeyReturnsIdempotencyConflict() throws Exception {
            UUID cardId = createCard("100");
            String key = UUID.randomUUID().toString();

            spend(cardId, "25", key).andExpect(status().isCreated());

            assertProblem(spend(cardId, "30", key), 409, "IDEMPOTENCY_CONFLICT");

            // conflict detail must not echo the raw key
            String body = spendWithKeyDirect(cardId, key, "30")
                    .andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(key);
        }

        private ResultActions spendWithKeyDirect(UUID cardId, String key, String amount) throws Exception {
            return mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\": " + amount + "}"));
        }
    }

    @Nested
    class InsufficientFunds {

        @Test
        void insufficientFundsReturns422AndDeclinedTransactionRemainsPersisted() throws Exception {
            UUID cardId = createCard("20");
            String key = UUID.randomUUID().toString();

            assertProblem(spend(cardId, "50", key), 422, "INSUFFICIENT_FUNDS");

            // final error layer must not have altered durable-decline transaction semantics
            long declinedSpendRows = cardTransactionRepository
                    .findByCardIdOrderByCreatedAtDescIdDesc(cardId, org.springframework.data.domain.Pageable.unpaged())
                    .getContent()
                    .stream()
                    .filter(t -> t.getType() == TransactionType.SPEND)
                    .count();
            assertThat(declinedSpendRows).isEqualTo(1);
        }
    }

    @Test
    void successfulMutationAlsoCarriesRequestIdHeader() throws Exception {
        var result = mockMvc.perform(post("/api/v1/cards")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\": \"Andre\", \"initialBalance\": 10}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Request-Id")).isNotBlank();
    }
}
