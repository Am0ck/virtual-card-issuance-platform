package com.andre.virtualcard.transaction;

import com.andre.virtualcard.card.Card;
import com.andre.virtualcard.card.CardNotFoundException;
import com.andre.virtualcard.card.CardOperationResult;
import com.andre.virtualcard.card.CardRepository;
import com.andre.virtualcard.common.MonetaryAmounts;
import com.andre.virtualcard.common.audit.CardOperationAuditEvent;
import com.andre.virtualcard.common.observability.OperationObservability;
import com.andre.virtualcard.idempotency.IdempotencyClaim;
import com.andre.virtualcard.idempotency.IdempotencyClaim.Replayed;
import com.andre.virtualcard.idempotency.IdempotencyConflictException;
import com.andre.virtualcard.idempotency.IdempotencyKeyHasher;
import com.andre.virtualcard.idempotency.IdempotencyOperation;
import com.andre.virtualcard.idempotency.IdempotencyRequest;
import com.andre.virtualcard.idempotency.IdempotencyService;
import com.andre.virtualcard.idempotency.RequestFingerprint;
import com.andre.virtualcard.transaction.CardMutationResult.Declined;
import com.andre.virtualcard.transaction.CardMutationResult.Successful;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class CardTransactionService {

    private static final Logger log = LoggerFactory.getLogger(CardTransactionService.class);
    private static final int MAX_HISTORY_SIZE = 100;

    private final CardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final IdempotencyService idempotencyService;
    private final OperationObservability observability;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public CardTransactionService(
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
    public CardMutationResult spend(UUID cardId, String idempotencyKey, AmountRequest request) {
        return mutate(cardId, idempotencyKey, request, TransactionType.SPEND);
    }

    @Transactional
    public CardMutationResult topUp(UUID cardId, String idempotencyKey, AmountRequest request) {
        return mutate(cardId, idempotencyKey, request, TransactionType.TOP_UP);
    }

    @Transactional(readOnly = true)
    public TransactionHistoryResponse getHistory(UUID cardId, int page, int size) {
        if (size > MAX_HISTORY_SIZE) {
            throw new IllegalArgumentException("size must not exceed " + MAX_HISTORY_SIZE);
        }
        if (!cardRepository.existsById(cardId)) {
            throw new CardNotFoundException(cardId);
        }
        return TransactionHistoryResponse.from(
                cardTransactionRepository.findByCardIdOrderByCreatedAtDescIdDesc(cardId, PageRequest.of(page, size)),
                page,
                size
        );
    }

    private CardMutationResult mutate(UUID cardId, String idempotencyKey, AmountRequest request, TransactionType type) {
        String operation = type.name();
        long startNano = System.nanoTime();
        boolean replay = false;
        try {
            BigDecimal canonicalAmount = MonetaryAmounts.requirePositive(request.amount(), "amount");
            String fingerprint = RequestFingerprint.ofCanonicalAmount(canonicalAmount);
            IdempotencyOperation operationEnum = type == TransactionType.SPEND
                    ? IdempotencyOperation.SPEND
                    : IdempotencyOperation.TOP_UP;

            IdempotencyClaim claim = idempotencyService.claim(operationEnum, cardId, idempotencyKey, fingerprint);

            if (claim instanceof Replayed replayed) {
                replay = true;
                CardMutationResult result = replayedResult(replayed.request());
                recordTerminal(operation, result, replay, startNano, cardId, canonicalAmount, idempotencyKey);
                return result;
            }

            UUID claimId = ((IdempotencyClaim.Claimed) claim).idempotencyRequestId();

            Card card = cardRepository.findByIdForUpdate(cardId)
                    .orElseThrow(() -> new CardNotFoundException(cardId));

            CardOperationResult result = type == TransactionType.SPEND
                    ? card.spend(canonicalAmount)
                    : card.topUp(canonicalAmount);

            // PostgreSQL TIMESTAMPTZ has microsecond resolution: truncate up front so the
            // mutation response and later reads/idempotent replays carry the identical instant
            Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            UUID transactionId = UUID.randomUUID();

            CardTransaction transaction;
            if (result instanceof CardOperationResult.Successful) {
                transaction = type == TransactionType.SPEND
                        ? CardTransaction.successfulSpend(transactionId, cardId, canonicalAmount, createdAt)
                        : CardTransaction.successfulTopUp(transactionId, cardId, canonicalAmount, createdAt);
            } else {
                DeclineReason reason = ((CardOperationResult.Declined) result).reason();
                transaction = type == TransactionType.SPEND
                        ? CardTransaction.declinedSpend(transactionId, cardId, canonicalAmount, reason, createdAt)
                        : CardTransaction.declinedTopUp(transactionId, cardId, canonicalAmount, reason, createdAt);
            }
            // flush so the row exists in the database before the native-SQL result finalization
            // enforces its foreign keys
            cardTransactionRepository.saveAndFlush(transaction);
            idempotencyService.complete(claimId, null, transactionId);

            eventPublisher.publishEvent(new CardOperationAuditEvent(
                    operation,
                    cardId,
                    transactionId,
                    canonicalAmount,
                    transaction.getStatus().name(),
                    transaction.getDeclineReason() == null ? null : transaction.getDeclineReason().name()
            ));

            CardMutationResult outcome;
            if (transaction.getStatus() == TransactionStatus.SUCCESSFUL) {
                outcome = new Successful(CardTransactionResponse.from(transaction));
            } else {
                outcome = new Declined(transaction.getDeclineReason());
            }
            recordTerminal(operation, outcome, replay, startNano, cardId, canonicalAmount, idempotencyKey);
            return outcome;
        } catch (IdempotencyConflictException e) {
            observability.recordOperation(operation, OperationObservability.OUTCOME_CONFLICT,
                    OperationObservability.REASON_NONE, replay, elapsedMs(startNano));
            log.warn("card_operation operation={} cardId={} outcome=CONFLICT durationMs={} idempotencyKeyHash={}",
                    operation, cardId, elapsedMs(startNano), IdempotencyKeyHasher.hash(idempotencyKey));
            throw e;
        } catch (CardNotFoundException e) {
            observability.recordOperation(operation, OperationObservability.OUTCOME_NOT_FOUND,
                    OperationObservability.REASON_NONE, replay, elapsedMs(startNano));
            log.warn("card_operation operation={} cardId={} outcome=NOT_FOUND durationMs={} idempotencyKeyHash={}",
                    operation, cardId, elapsedMs(startNano), IdempotencyKeyHasher.hash(idempotencyKey));
            throw e;
        } catch (IllegalArgumentException e) {
            observability.recordOperation(operation, OperationObservability.OUTCOME_INVALID,
                    OperationObservability.REASON_NONE, replay, elapsedMs(startNano));
            throw e;
        } catch (RuntimeException e) {
            observability.recordOperation(operation, OperationObservability.OUTCOME_ERROR,
                    OperationObservability.REASON_NONE, replay, elapsedMs(startNano));
            log.error("card_operation operation={} cardId={} outcome=ERROR durationMs={} idempotencyKeyHash={}",
                    operation, cardId, elapsedMs(startNano), IdempotencyKeyHasher.hash(idempotencyKey));
            throw e;
        }
    }

    private void recordTerminal(
            String operation,
            CardMutationResult result,
            boolean replay,
            long startNano,
            UUID cardId,
            BigDecimal canonicalAmount,
            String idempotencyKey
    ) {
        long durationMs = elapsedMs(startNano);
        String keyHash = IdempotencyKeyHasher.hash(idempotencyKey);
        if (result instanceof Successful successful) {
            observability.recordOperation(operation, OperationObservability.OUTCOME_SUCCESSFUL,
                    OperationObservability.REASON_NONE, replay, durationMs);
            log.info("card_operation operation={} cardId={} transactionId={} amount={} "
                            + "outcome=SUCCESSFUL declineReason=none durationMs={} idempotencyKeyHash={} replay={}",
                    operation, cardId, successful.transaction().id(),
                    successful.transaction().amount().toPlainString(), durationMs, keyHash, replay);
        } else {
            DeclineReason reason = ((Declined) result).reason();
            observability.recordOperation(operation, OperationObservability.OUTCOME_DECLINED,
                    reason.name(), replay, durationMs);
            log.info("card_operation operation={} cardId={} amount={} outcome=DECLINED declineReason={} "
                            + "durationMs={} idempotencyKeyHash={} replay={}",
                    operation, cardId, canonicalAmount.toPlainString(), reason.name(), durationMs, keyHash, replay);
        }
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private CardMutationResult replayedResult(IdempotencyRequest request) {
        if (request.getResultTransactionId() == null) {
            throw new IllegalStateException("Idempotency row " + request.getId()
                    + " has no completed transaction result");
        }
        CardTransaction original = cardTransactionRepository.findById(request.getResultTransactionId())
                .orElseThrow(() -> new IllegalStateException("Idempotency row " + request.getId()
                        + " references missing transaction " + request.getResultTransactionId()));
        if (original.getStatus() == TransactionStatus.SUCCESSFUL) {
            return new Successful(CardTransactionResponse.from(original));
        }
        return new Declined(original.getDeclineReason());
    }
}
