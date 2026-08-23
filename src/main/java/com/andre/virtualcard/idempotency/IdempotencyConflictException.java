package com.andre.virtualcard.idempotency;

import java.util.UUID;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(IdempotencyOperation operation, UUID resourceId, String key) {
        super("Idempotency key '" + key + "' was already used for "
                + operation + (resourceId == null ? "" : " on resource " + resourceId)
                + " with a different request payload");
    }
}
