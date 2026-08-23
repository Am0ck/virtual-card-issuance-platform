package com.andre.virtualcard.transaction;

import com.andre.virtualcard.card.Card;
import com.andre.virtualcard.card.CardNotFoundException;
import com.andre.virtualcard.card.CardOperationResult;
import com.andre.virtualcard.card.CardRepository;
import com.andre.virtualcard.common.MonetaryAmounts;
import com.andre.virtualcard.idempotency.IdempotencyClaim;
import com.andre.virtualcard.idempotency.IdempotencyClaim.Replayed;
import com.andre.virtualcard.idempotency.IdempotencyOperation;
import com.andre.virtualcard.idempotency.IdempotencyRequest;
import com.andre.virtualcard.idempotency.IdempotencyService;
import com.andre.virtualcard.idempotency.RequestFingerprint;
import com.andre.virtualcard.transaction.CardMutationResult.Declined;
import com.andre.virtualcard.transaction.CardMutationResult.Successful;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class CardTransactionService {

    private static final int MAX_HISTORY_SIZE = 100;

    private final CardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public CardTransactionService(
            CardRepository cardRepository,
            CardTransactionRepository cardTransactionRepository,
            IdempotencyService idempotencyService,
            Clock clock
    ) {
        this.cardRepository = cardRepository;
        this.cardTransactionRepository = cardTransactionRepository;
        this.idempotencyService = idempotencyService;
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
        BigDecimal canonicalAmount = MonetaryAmounts.requirePositive(request.amount(), "amount");
        String fingerprint = RequestFingerprint.ofCanonicalAmount(canonicalAmount);
        IdempotencyOperation operation = type == TransactionType.SPEND
                ? IdempotencyOperation.SPEND
                : IdempotencyOperation.TOP_UP;

        IdempotencyClaim claim = idempotencyService.claim(operation, cardId, idempotencyKey, fingerprint);

        if (claim instanceof Replayed replayed) {
            return replayedResult(replayed.request());
        }

        UUID claimId = ((IdempotencyClaim.Claimed) claim).idempotencyRequestId();

        Card card = cardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        CardOperationResult result = type == TransactionType.SPEND
                ? card.spend(canonicalAmount)
                : card.topUp(canonicalAmount);

        Instant createdAt = clock.instant();
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

        if (transaction.getStatus() == TransactionStatus.SUCCESSFUL) {
            return new Successful(CardTransactionResponse.from(transaction));
        }
        return new Declined(transaction.getDeclineReason());
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
