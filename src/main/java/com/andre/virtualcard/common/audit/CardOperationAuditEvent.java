package com.andre.virtualcard.common.audit;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published inside the business transaction, delivered after commit.
 * Contains no cardholder name, balance, or idempotency-key material.
 */
public record CardOperationAuditEvent(
        String operation,
        UUID cardId,
        UUID transactionId,
        BigDecimal amount,
        String outcome,
        String declineReason
) {
}
