package com.andre.virtualcard.idempotency;

/**
 * The raw Idempotency-Key is deliberately never part of this message:
 * it must not leak into ProblemDetail responses or logs.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("This idempotency key has already been used for a different request payload.");
    }
}
