package com.andre.virtualcard.idempotency;

import java.util.UUID;

public sealed interface IdempotencyClaim permits IdempotencyClaim.Claimed, IdempotencyClaim.Replayed {

    record Claimed(UUID idempotencyRequestId) implements IdempotencyClaim {
    }

    record Replayed(IdempotencyRequest request) implements IdempotencyClaim {
    }
}
