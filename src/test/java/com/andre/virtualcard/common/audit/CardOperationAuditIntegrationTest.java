package com.andre.virtualcard.common.audit;

import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class CardOperationAuditIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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

    @Nested
    class CommittedMutationsAreAudited {

        @Test
        void cardCreationIsAuditedAfterCommit(CapturedOutput output) throws Exception {
            UUID cardId = createCard("100");

            assertThat(output).contains("audit_event operation=CREATE_CARD")
                    .contains("cardId=" + cardId)
                    .contains("outcome=SUCCESSFUL");
        }

        @Test
        void successfulSpendIsAudited(CapturedOutput output) throws Exception {
            UUID cardId = createCard("100");

            mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 25}"))
                    .andExpect(status().isCreated());

            assertThat(output).contains("audit_event operation=SPEND")
                    .contains("amount=25.00")
                    .contains("outcome=SUCCESSFUL");
        }

        @Test
        void committedDeclineIsAudited(CapturedOutput output) throws Exception {
            UUID cardId = createCard("20");

            mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 50}"))
                    .andExpect(status().isUnprocessableEntity());

            assertThat(output).contains("audit_event operation=SPEND")
                    .contains("outcome=DECLINED")
                    .contains("declineReason=INSUFFICIENT_FUNDS");
        }
    }

    @Nested
    class NonMutationsAreNotAudited {

        @Test
        void idempotentReplayProducesNoSecondAudit(CapturedOutput output) throws Exception {
            UUID cardId = createCard("100");
            String key = UUID.randomUUID().toString();

            mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 25}"))
                    .andExpect(status().isCreated());

            long auditsAfterOriginal = countLinesContaining(output, "audit_event operation=SPEND");

            mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 25}"))
                    .andExpect(status().isCreated()); // replay

            assertThat(countLinesContaining(output, "audit_event operation=SPEND"))
                    .isEqualTo(auditsAfterOriginal);
        }

        @Test
        void missingCardRollbackProducesNoAudit(CapturedOutput output) throws Exception {
            UUID missingCardId = UUID.randomUUID();

            mockMvc.perform(post("/api/v1/cards/" + missingCardId + "/spends")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 25}"))
                    .andExpect(status().isNotFound());

            // rolled-back claim/mutation must not produce any committed-mutation audit
            assertThat(output).doesNotContain("audit_event");
        }
    }

    private long countLinesContaining(CapturedOutput output, String fragment) {
        return output.getAll().lines().filter(line -> line.contains(fragment)).count();
    }
}
