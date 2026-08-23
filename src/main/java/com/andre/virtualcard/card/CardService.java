package com.andre.virtualcard.card;

import com.andre.virtualcard.idempotency.IdempotencyClaim;
import com.andre.virtualcard.idempotency.IdempotencyOperation;
import com.andre.virtualcard.idempotency.IdempotencyRequest;
import com.andre.virtualcard.idempotency.IdempotencyService;
import com.andre.virtualcard.idempotency.RequestFingerprint;
import com.andre.virtualcard.transaction.CardTransaction;
import com.andre.virtualcard.transaction.CardTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class CardService {

    private final CardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public CardService(
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
    public CardResponse create(String idempotencyKey, CreateCardRequest request) {
        Instant createdAt = clock.instant();
        UUID cardId = UUID.randomUUID();

        Card candidate = Card.create(cardId, request.cardholderName(), request.initialBalance(), createdAt);
        String fingerprint = RequestFingerprint.forCardCreation(
                candidate.getCardholderName(),
                candidate.getBalance()
        );

        IdempotencyClaim claim = idempotencyService.claim(
                IdempotencyOperation.CREATE_CARD, null, idempotencyKey, fingerprint);

        if (claim instanceof IdempotencyClaim.Replayed replayed) {
            return cardRepository.findById(requireCardResult(replayed.request()))
                    .map(CardResponse::from)
                    .orElseThrow(() -> brokenReplay(replayed.request()));
        }

        UUID claimId = ((IdempotencyClaim.Claimed) claim).idempotencyRequestId();
        // flush so the row exists before the native-SQL result finalization enforces its FK
        cardRepository.saveAndFlush(candidate);

        if (candidate.getBalance().signum() > 0) {
            cardTransactionRepository.save(CardTransaction.initialFunding(
                    UUID.randomUUID(),
                    cardId,
                    candidate.getBalance(),
                    createdAt
            ));
        }

        idempotencyService.complete(claimId, cardId, null);
        return CardResponse.from(candidate);
    }

    @Transactional(readOnly = true)
    public CardResponse get(UUID cardId) {
        return cardRepository.findById(cardId)
                .map(CardResponse::from)
                .orElseThrow(() -> new CardNotFoundException(cardId));
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
