package com.andre.virtualcard.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "idempotency_request",
        indexes = {
                @Index(name = "idx_idempotency_request_expires_at", columnList = "expires_at")
        }
)
public class IdempotencyRequest {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 32)
    private IdempotencyOperation operationType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "result_card_id")
    private UUID resultCardId;

    @Column(name = "result_transaction_id")
    private UUID resultTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRequest() {
    }

    public UUID getId() {
        return id;
    }

    public IdempotencyOperation getOperationType() {
        return operationType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getResultCardId() {
        return resultCardId;
    }

    public UUID getResultTransactionId() {
        return resultTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
