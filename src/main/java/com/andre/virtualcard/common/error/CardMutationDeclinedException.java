package com.andre.virtualcard.common.error;

import com.andre.virtualcard.transaction.DeclineReason;

/**
 * Thrown by controllers AFTER the transactional service has returned a declined
 * result, so the persisted DECLINED transaction is already committed. Mapping it
 * to HTTP here never rolls back business data.
 */
public class CardMutationDeclinedException extends RuntimeException {

    private final DeclineReason reason;

    public CardMutationDeclinedException(DeclineReason reason) {
        super(switch (reason) {
            case INSUFFICIENT_FUNDS -> "The card has insufficient funds for this transaction.";
            case CARD_BLOCKED -> "The card is blocked.";
            case CARD_CLOSED -> "The card is closed.";
        });
        this.reason = reason;
    }

    public DeclineReason getReason() {
        return reason;
    }
}
