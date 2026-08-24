package com.andre.virtualcard.common.audit;

import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import com.andre.virtualcard.support.AuditEventProbe;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deterministic async-audit coverage.
 *
 * Positive delivery (thread, MDC propagation) is asserted through the
 * {@link AuditEventProbe}, which mirrors the production
 * {@code @Async("auditExecutor") + AFTER_COMMIT} wiring and signals via a semaphore —
 * no sleeps.
 *
 * Absence proofs (replay, rollback) are asserted synchronously through
 * {@link ApplicationEvents}: event publication is recorded at publish time inside the
 * transaction, so counts are final the moment a request returns — no flush operations,
 * no executor races.
 */
@RecordApplicationEvents
@Import(AuditEventProbe.class)
class CardOperationAuditIntegrationTest extends AbstractPostgreSQLIntegrationTest {

    private static final int PROBE_TIMEOUT_SECONDS = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditEventProbe probe;

    @Autowired
    private ApplicationEvents events;

    private UUID createCard(String initialBalance) throws Exception {
        String location = createCardAction(initialBalance)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private ResultActions createCardAction(String initialBalance) throws Exception {
        return mockMvc.perform(post("/api/v1/cards")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardholderName\": \"Jane Doe\", \"initialBalance\": "
                        + initialBalance + "}"));
    }

    private ResultActions spend(UUID cardId, String amount, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/cards/" + cardId + "/spends")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    /** Waits (semaphore-based, finite timeout) until an audit record matching p arrives. */
    private AuditEventProbe.AuditRecord awaitRecordMatching(Predicate<CardOperationAuditEvent> p) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SECONDS);
        while (true) {
            AuditEventProbe.AuditRecord match = probe.getRecords().stream()
                    .filter(r -> p.test(r.event()))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                return match;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0 || !probe.awaitAnyDelivery(TimeUnit.NANOSECONDS.toSeconds(remaining))) {
                throw new AssertionError("expected audit event was not delivered asynchronously");
            }
        }
    }

    private static Predicate<CardOperationAuditEvent> forCard(String operation, UUID cardId) {
        return e -> e.operation().equals(operation) && e.cardId().equals(cardId);
    }

    @Nested
    class CommittedMutationsAreAuditedAsynchronously {

        @Test
        void cardCreationIsAuditedOnAuditExecutorThreadWithRequestIdPropagation() throws Exception {
            var result = createCardAction("100")
                    .andExpect(status().isCreated())
                    .andReturn();
            String responseRequestId = result.getResponse().getHeader("X-Request-Id");
            assertThat(responseRequestId).isNotBlank();

            UUID cardId = UUID.fromString(result.getResponse().getHeader("Location")
                    .substring(result.getResponse().getHeader("Location").lastIndexOf('/') + 1));

            AuditEventProbe.AuditRecord record = awaitRecordMatching(
                    forCard("CREATE_CARD", cardId));

            assertThat(record.event().amount()).isNull(); // CREATE_CARD carries no balance/amount
            assertThat(record.event().outcome()).isEqualTo("SUCCESSFUL");
            // genuinely asynchronous: executed on the named bounded executor, not the caller thread
            assertThat(record.threadName()).startsWith("audit-");
            // TaskDecorator propagated the caller's requestId MDC onto the audit worker
            assertThat(record.requestId()).isEqualTo(responseRequestId);
        }

        @Test
        void successfulSpendIsAuditedOnAuditExecutorThread() throws Exception {
            UUID cardId = createCard("100");

            spend(cardId, "25", UUID.randomUUID().toString())
                    .andExpect(status().isCreated());

            AuditEventProbe.AuditRecord record = awaitRecordMatching(forCard("SPEND", cardId));
            assertThat(record.event().outcome()).isEqualTo("SUCCESSFUL");
            assertThat(record.event().transactionId()).isNotNull();
            assertThat(record.threadName()).startsWith("audit-");
        }

        @Test
        void committedDeclineIsAuditedOnAuditExecutorThread() throws Exception {
            UUID cardId = createCard("20");

            spend(cardId, "50", UUID.randomUUID().toString())
                    .andExpect(status().isUnprocessableEntity());

            AuditEventProbe.AuditRecord record = awaitRecordMatching(forCard("SPEND", cardId));
            assertThat(record.event().outcome()).isEqualTo("DECLINED");
            assertThat(record.event().declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(record.threadName()).startsWith("audit-");
        }

        @Test
        void requestCompletionDoesNotWaitForBlockedAuditProcessing() throws Exception {
            // arm the gate: dispatched audits park inside the executor worker before recording
            probe.enableGate();
            try {
                // the single CREATE_CARD request used throughout this test; its response must
                // complete even though audit processing for it is stuck at the gate
                var response = mockMvc.perform(post("/api/v1/cards")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cardholderName\": \"Jane Doe\", \"initialBalance\": 100}"))
                        .andExpect(status().isCreated())
                        .andReturn();

                // prove dispatch happened into the executor (and is still parked at the
                // gate): the caller had already received its committed result by now
                assertThat(probe.awaitEnteredGate(PROBE_TIMEOUT_SECONDS))
                        .as("audit listener entered the executor while blocked")
                        .isTrue();

                // the gated event belongs to THIS request's card
                String location = response.getResponse().getHeader("Location");
                UUID cardA = UUID.fromString(
                        location.substring(location.lastIndexOf('/') + 1));
                assertThat(probe.getGatedEvent()).isNotNull();
                assertThat(probe.getGatedEvent().operation()).isEqualTo("CREATE_CARD");
                assertThat(probe.getGatedEvent().cardId()).isEqualTo(cardA);

                probe.disableGateAndRelease();

                // the originally blocked audit completes asynchronously on the executor
                AuditEventProbe.AuditRecord record = awaitRecordMatching(
                        forCard("CREATE_CARD", cardA));
                assertThat(record.threadName()).startsWith("audit-");
            } finally {
                probe.disableGateAndRelease();
            }
        }
    }

    @Nested
    class NonMutationsProduceNoPublication {

        @Test
        void idempotentReplayPublishesNoSecondEvent() throws Exception {
            UUID cardId = createCard("100");
            String key = UUID.randomUUID().toString();

            spend(cardId, "25", key).andExpect(status().isCreated());
            awaitRecordMatching(forCard("SPEND", cardId)); // original delivered asynchronously

            long publicationsBefore = events.stream(CardOperationAuditEvent.class)
                    .filter(forCard("SPEND", cardId))
                    .count();

            spend(cardId, "25.00", key).andExpect(status().isCreated()); // replay

            // publication recording is synchronous with the request: the count is already
            // final when the replay response returns — fully deterministic, no flush op
            assertThat(events.stream(CardOperationAuditEvent.class)
                    .filter(forCard("SPEND", cardId))
                    .count()).isEqualTo(publicationsBefore);
        }

        @Test
        void missingCardRollbackPublishesNoEvent() throws Exception {
            UUID missingCardId = UUID.randomUUID();

            // rollback discards claim + event: publication never happens because the
            // service publishes inside a transaction that does not commit, so the
            // AFTER_COMMIT phase is never reached either
            spend(missingCardId, "5", UUID.randomUUID().toString())
                    .andExpect(status().isNotFound());

            assertThat(events.stream(CardOperationAuditEvent.class)
                    .filter(e -> e.cardId().equals(missingCardId))
                    .count()).isZero();
        }
    }
}
