package com.andre.virtualcard.common.observability;

import com.andre.virtualcard.card.CardRepository;
import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import com.andre.virtualcard.transaction.AmountRequest;
import com.andre.virtualcard.transaction.CardMutationResult;
import com.andre.virtualcard.transaction.CardTransactionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperationObservabilityIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardTransactionService cardTransactionService;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID createCard(String initialBalance) throws Exception {
        String location = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/cards")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\": \"Jane Doe\", \"initialBalance\": "
                                + initialBalance + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private double counterValue(String outcome, String reason) {
        var counter = meterRegistry.find("virtual_card.operations")
                .tag("operation", "SPEND")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .tag("replay", "false")
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void successfulSpendIncrementsSuccessfulCounterAndTimer() {
        double successfulBefore = counterValue("SUCCESSFUL", "NONE");
        long timerBefore = meterRegistry.find("virtual_card.operation.duration")
                .tag("operation", "SPEND")
                .timer() == null ? 0 : meterRegistry.find("virtual_card.operation.duration")
                .tag("operation", "SPEND")
                .timer().count();

        CardMutationResult result = cardTransactionService.spend(
                createCardUnchecked("100"),
                UUID.randomUUID().toString(),
                new AmountRequest(new BigDecimal("25")));

        assertThat(result).isInstanceOf(CardMutationResult.Successful.class);

        assertThat(counterValue("SUCCESSFUL", "NONE")).isEqualTo(successfulBefore + 1);
        var timer = meterRegistry.find("virtual_card.operation.duration")
                .tag("operation", "SPEND")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThan(timerBefore);
    }

    @Test
    void declinedSpendIncrementsDeclinedInsufficientFundsCounter() {
        UUID cardId = createCardUnchecked("20");
        double declinedBefore = counterValue("DECLINED", "INSUFFICIENT_FUNDS");

        CardMutationResult result = cardTransactionService.spend(
                cardId, UUID.randomUUID().toString(), new AmountRequest(new BigDecimal("50")));

        assertThat(result).isInstanceOf(CardMutationResult.Declined.class);

        assertThat(counterValue("DECLINED", "INSUFFICIENT_FUNDS")).isEqualTo(declinedBefore + 1);
    }

    private UUID createCardUnchecked(String balance) {
        try {
            return createCard(balance);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
