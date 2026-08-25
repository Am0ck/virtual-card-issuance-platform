package com.andre.virtualcard.card;

import com.andre.virtualcard.common.audit.CardOperationAuditEvent;
import com.andre.virtualcard.common.observability.OperationObservability;
import com.andre.virtualcard.idempotency.IdempotencyClaim;
import com.andre.virtualcard.idempotency.IdempotencyConflictException;
import com.andre.virtualcard.idempotency.IdempotencyKeyHasher;
import com.andre.virtualcard.idempotency.IdempotencyOperation;
import com.andre.virtualcard.idempotency.IdempotencyRequest;
import com.andre.virtualcard.idempotency.IdempotencyService;
import com.andre.virtualcard.idempotency.RequestFingerprint;
import com.andre.virtualcard.transaction.CardTransaction;
import com.andre.virtualcard.transaction.CardTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);

    private final CardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final IdempotencyService idempotencyService;
    private final OperationObservability observability;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public CardService(
            CardRepository cardRepository,
            CardTransactionRepository cardTransactionRepository,
            IdempotencyService idempotencyService,
            OperationObservability observability,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.cardRepository = cardRepository;
        this.cardTransactionRepository = cardTransactionRepository;
        this.idempotencyService = idempotencyService;
        this.observability = observability;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public CardResponse create(String idempotencyKey, CreateCardRequest request) {
        long startNano = System.nanoTime();
        boolean replay = false;
        try {
            // PostgreSQL TIMESTAMPTZ has microsecond resolution: truncate up front so the
            // create response and later reads/idempotent replays carry the identical instant
            Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            UUID cardId = UUID.randomUUID();

            Card candidate = Card.create(cardId, request.cardholderName(), request.initialBalance(), createdAt);
            String fingerprint = RequestFingerprint.forCardCreation(
                    candidate.getCardholderName(),
                    candidate.getBalance()
            );

            IdempotencyClaim claim = idempotencyService.claim(
                    IdempotencyOperation.CREATE_CARD, null, idempotencyKey, fingerprint);

            if (claim instanceof IdempotencyClaim.Replayed replayed) {
                replay = true;
                CardResponse response = cardRepository.findById(requireCardResult(replayed.request()))
                        .map(CardResponse::from)
                        .orElseThrow(() -> brokenReplay(replayed.request()));
                // log the ORIGINAL persisted card id, not the discarded candidate UUID
                recordSuccess(startNano, replay, response.id(), null, idempotencyKey);
                return response;
            }

            UUID claimId = ((IdempotencyClaim.Claimed) claim).idempotencyRequestId();
            // flush so the row exists before the native-SQL result finalization enforces its FK
            cardRepository.saveAndFlush(candidate);

            UUID initialFundingId = null;
            if (candidate.getBalance().signum() > 0) {
                CardTransaction initialFunding = CardTransaction.initialFunding(
                        UUID.randomUUID(),
                        cardId,
                        candidate.getBalance(),
                        createdAt
                );
                cardTransactionRepository.save(initialFunding);
                initialFundingId = initialFunding.getId();
            }

            idempotencyService.complete(claimId, cardId, null);

            eventPublisher.publishEvent(new CardOperationAuditEvent(
                    "CREATE_CARD", cardId, initialFundingId, null, "SUCCESSFUL", null));

            recordSuccess(startNano, replay, candidate.getId(), initialFundingId, idempotencyKey);
            return CardResponse.from(candidate);
        } catch (IdempotencyConflictException e) {
            recordFailure(startNano, OperationObservability.OUTCOME_CONFLICT, OperationObservability.REASON_NONE,
                    idempotencyKey);
            throw e;
        } catch (IllegalArgumentException e) {
            recordFailure(startNano, OperationObservability.OUTCOME_INVALID, OperationObservability.REASON_NONE,
                    idempotencyKey);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(startNano, OperationObservability.OUTCOME_ERROR, OperationObservability.REASON_NONE,
                    idempotencyKey);
            throw e;
        }
    }

    private void recordSuccess(long startNano, boolean replay, UUID cardId, UUID transactionId,
                               String idempotencyKey) {
        long durationMs = elapsedMs(startNano);
        observability.recordOperation("CREATE_CARD", OperationObservability.OUTCOME_SUCCESSFUL,
                OperationObservability.REASON_NONE, replay, durationMs);
        // no balance/amount field: card balance is prohibited from logs
        log.info("card_operation operation=CREATE_CARD cardId={} transactionId={} "
                        + "outcome=SUCCESSFUL declineReason=none durationMs={} idempotencyKeyHash={} replay={}",
                cardId, transactionId, durationMs, IdempotencyKeyHasher.hash(idempotencyKey), replay);
    }

    private void recordFailure(long startNano, String outcome, String reasonTag, String idempotencyKey) {
        long durationMs = elapsedMs(startNano);
        observability.recordOperation("CREATE_CARD", outcome, reasonTag, false, durationMs);
        log.warn("card_operation operation=CREATE_CARD outcome={} durationMs={} idempotencyKeyHash={}",
                outcome, durationMs, IdempotencyKeyHasher.hash(idempotencyKey));
    }

    @Transactional(readOnly = true)
    public CardResponse get(UUID cardId) {
        return cardRepository.findById(cardId)
                .map(CardResponse::from)
                .orElseThrow(() -> new CardNotFoundException(cardId));
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private static UUID requireCardResult(IdempotencyRequest request) {
        if (request.getResultCardId() == null) {
            throw new IllegalStateException("Idempotency row " + request.getId()
                    + " has no completed card result");
        }
        return request.getResultCardId();
    }

    private static IllegalStateException brokenReplay(IdempotencyRequest request) {
        return new IllegalStateException("Idempotency row " + request.getId()
                + " references missing card " + request.getResultCardId());
    }
}
