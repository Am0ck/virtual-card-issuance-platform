package com.andre.virtualcard.transaction;

import com.andre.virtualcard.card.Card;
import com.andre.virtualcard.card.CardNotFoundException;
import com.andre.virtualcard.card.CardOperationResult;
import com.andre.virtualcard.card.CardRepository;
import com.andre.virtualcard.transaction.CardMutationResult.Declined;
import com.andre.virtualcard.transaction.CardMutationResult.Successful;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class CardTransactionService {

    private static final int MAX_HISTORY_SIZE = 100;

    private final CardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final Clock clock;

    public CardTransactionService(
            CardRepository cardRepository,
            CardTransactionRepository cardTransactionRepository,
            Clock clock
    ) {
        this.cardRepository = cardRepository;
        this.cardTransactionRepository = cardTransactionRepository;
        this.clock = clock;
    }

    @Transactional
    public CardMutationResult spend(UUID cardId, AmountRequest request) {
        return mutate(cardId, request, TransactionType.SPEND);
    }

    @Transactional
    public CardMutationResult topUp(UUID cardId, AmountRequest request) {
        return mutate(cardId, request, TransactionType.TOP_UP);
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

    private CardMutationResult mutate(UUID cardId, AmountRequest request, TransactionType type) {
        Card card = cardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        CardOperationResult result = type == TransactionType.SPEND
                ? card.spend(request.amount())
                : card.topUp(request.amount());

        Instant createdAt = clock.instant();
        UUID transactionId = UUID.randomUUID();

        if (result instanceof CardOperationResult.Successful) {
            CardTransaction transaction = type == TransactionType.SPEND
                    ? CardTransaction.successfulSpend(transactionId, cardId, request.amount(), createdAt)
                    : CardTransaction.successfulTopUp(transactionId, cardId, request.amount(), createdAt);
            cardTransactionRepository.save(transaction);
            return new Successful(CardTransactionResponse.from(transaction));
        }

        DeclineReason reason = ((CardOperationResult.Declined) result).reason();
        CardTransaction transaction = type == TransactionType.SPEND
                ? CardTransaction.declinedSpend(transactionId, cardId, request.amount(), reason, createdAt)
                : CardTransaction.declinedTopUp(transactionId, cardId, request.amount(), reason, createdAt);
        cardTransactionRepository.save(transaction);
        return new Declined(reason);
    }
}
