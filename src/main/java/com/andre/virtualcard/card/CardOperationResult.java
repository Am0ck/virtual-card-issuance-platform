package com.andre.virtualcard.card;

import com.andre.virtualcard.transaction.DeclineReason;

import java.util.Objects;

public sealed interface CardOperationResult permits CardOperationResult.Successful, CardOperationResult.Declined {

    static CardOperationResult successful() {
        return new Successful();
    }

    static CardOperationResult declined(DeclineReason reason) {
        return new Declined(reason);
    }

    record Successful() implements CardOperationResult {
    }

    record Declined(DeclineReason reason) implements CardOperationResult {
        public Declined {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
