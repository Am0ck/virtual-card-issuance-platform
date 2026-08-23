package com.andre.virtualcard.card;

import com.andre.virtualcard.transaction.CardTransaction;
import com.andre.virtualcard.transaction.CardTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class CardService {

    private final CardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final Clock clock;

    public CardService(
            CardRepository cardRepository,
            CardTransactionRepository cardTransactionRepository,
            Clock clock
    ) {
        this.cardRepository = cardRepository;
        this.cardTransactionRepository = cardTransactionRepository;
        this.clock = clock;
    }

    @Transactional
    public CardResponse create(CreateCardRequest request) {
        Instant createdAt = clock.instant();
        UUID cardId = UUID.randomUUID();

        Card card = cardRepository.save(
                Card.create(cardId, request.cardholderName(), request.initialBalance(), createdAt));

        BigDecimal canonicalInitialBalance = card.getBalance();
        if (canonicalInitialBalance.signum() > 0) {
            cardTransactionRepository.save(CardTransaction.initialFunding(
                    UUID.randomUUID(),
                    cardId,
                    canonicalInitialBalance,
                    createdAt
            ));
        }

        return CardResponse.from(card);
    }

    @Transactional(readOnly = true)
    public CardResponse get(UUID cardId) {
        return cardRepository.findById(cardId)
                .map(CardResponse::from)
                .orElseThrow(() -> new CardNotFoundException(cardId));
    }
}
