package com.andre.virtualcard.transaction;

public sealed interface CardMutationResult permits CardMutationResult.Successful, CardMutationResult.Declined {

    record Successful(CardTransactionResponse transaction) implements CardMutationResult {
    }

    record Declined(DeclineReason reason) implements CardMutationResult {
    }
}
