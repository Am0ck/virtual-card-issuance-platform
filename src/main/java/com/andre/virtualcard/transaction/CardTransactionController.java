package com.andre.virtualcard.transaction;

import com.andre.virtualcard.transaction.CardMutationResult.Declined;
import com.andre.virtualcard.transaction.CardMutationResult.Successful;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cards/{cardId}")
public class CardTransactionController {

    private final CardTransactionService cardTransactionService;

    public CardTransactionController(CardTransactionService cardTransactionService) {
        this.cardTransactionService = cardTransactionService;
    }

    @PostMapping("/spends")
    public ResponseEntity<Object> spend(
            @PathVariable UUID cardId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AmountRequest request
    ) {
        return toHttpResponse(cardTransactionService.spend(cardId, idempotencyKey, request));
    }

    @PostMapping("/top-ups")
    public ResponseEntity<Object> topUp(
            @PathVariable UUID cardId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AmountRequest request
    ) {
        return toHttpResponse(cardTransactionService.topUp(cardId, idempotencyKey, request));
    }

    @GetMapping("/transactions")
    public TransactionHistoryResponse history(
            @PathVariable UUID cardId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return cardTransactionService.getHistory(cardId, page, size);
    }

    private ResponseEntity<Object> toHttpResponse(CardMutationResult result) {
        if (result instanceof Successful successful) {
            return ResponseEntity.status(HttpStatus.CREATED).body(successful.transaction());
        }
        DeclineReason reason = ((Declined) result).reason();
        HttpStatus status = reason == DeclineReason.INSUFFICIENT_FUNDS
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of("message", declineMessage(reason)));
    }

    private String declineMessage(DeclineReason reason) {
        return switch (reason) {
            case INSUFFICIENT_FUNDS -> "The card has insufficient funds for this transaction.";
            case CARD_BLOCKED -> "The card is blocked.";
            case CARD_CLOSED -> "The card is closed.";
        };
    }
}
