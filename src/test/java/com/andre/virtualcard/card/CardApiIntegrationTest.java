package com.andre.virtualcard.card;

import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import com.andre.virtualcard.transaction.CardTransaction;
import com.andre.virtualcard.transaction.CardTransactionRepository;
import com.andre.virtualcard.transaction.TransactionStatus;
import com.andre.virtualcard.transaction.TransactionType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CardApiIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    private static final String CARDS_URL = "/api/v1/cards";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardTransactionRepository cardTransactionRepository;

    private static UUID extractCardId(String location) {
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    @Nested
    class Create {

        @Test
        void createsActiveCardWithZeroBalanceAndNoInitialFunding() throws Exception {
            var result = mockMvc.perform(post(CARDS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cardholderName": "Andre Cassar Mockridge", "initialBalance": 0}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.cardholderName").value("Andre Cassar Mockridge"))
                    .andExpect(jsonPath("$.balance").value(0.00))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty())
                    .andReturn();

            String location = result.getResponse().getHeader("Location");
            assertThat(location).matches("/api/v1/cards/[0-9a-f-]{36}");

            UUID cardId = extractCardId(location);
            assertThat(cardRepository.findById(cardId)).isPresent();
            assertThat(cardTransactionRepository
                    .findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged())
                    .getContent()).isEmpty();
        }

        @Test
        void createsCardWithPositiveBalanceAndExactlyOneSuccessfulInitialFunding() throws Exception {
            String location = mockMvc.perform(post(CARDS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cardholderName": "José García", "initialBalance": 100.50}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.balance").value(100.50))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andReturn().getResponse().getHeader("Location");

            UUID cardId = extractCardId(location);
            var transactions = cardTransactionRepository
                    .findByCardIdOrderByCreatedAtDescIdDesc(cardId, Pageable.unpaged())
                    .getContent();

            assertThat(transactions).hasSize(1);
            CardTransaction funding = transactions.get(0);
            assertThat(funding.getType()).isEqualTo(TransactionType.INITIAL_FUNDING);
            assertThat(funding.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(funding.getAmount()).isEqualByComparingTo("100.50");
            assertThat(funding.getDeclineReason()).isNull();
        }

        @Test
        void rejectsInitialBalanceOutsidePostgreSQLNumericRange() throws Exception {
            mockMvc.perform(post(CARDS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cardholderName": "Andre", "initialBalance": 100000000000000000.00}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsMissingInitialBalance() throws Exception {
            mockMvc.perform(post(CARDS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cardholderName": "Andre"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsMissingCardholderName() throws Exception {
            mockMvc.perform(post(CARDS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"initialBalance": 10.00}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsNegativeInitialBalance() throws Exception {
            mockMvc.perform(post(CARDS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cardholderName": "Andre", "initialBalance": -0.01}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsAmountRequiringRounding() throws Exception {
            mockMvc.perform(post(CARDS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cardholderName": "Andre", "initialBalance": 20.001}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsBlankCardholderNameFromDomainValidation() throws Exception {
            mockMvc.perform(post(CARDS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cardholderName": "   ", "initialBalance": 10}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Get {

        @Test
        void returnsExistingCardWithExpectedRepresentation() throws Exception {
            String location = mockMvc.perform(post(CARDS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cardholderName": "Zoë Smith", "initialBalance": 40.25}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getHeader("Location");

            UUID cardId = extractCardId(location);

            mockMvc.perform(get(CARDS_URL + "/" + cardId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(cardId.toString()))
                    .andExpect(jsonPath("$.cardholderName").value("Zoë Smith"))
                    .andExpect(jsonPath("$.balance").value(40.25))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        void returnsNotFoundForWellFormedButUnknownCardId() throws Exception {
            mockMvc.perform(get(CARDS_URL + "/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returnsBadRequestForMalformedCardId() throws Exception {
            mockMvc.perform(get(CARDS_URL + "/not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }
    }
}
