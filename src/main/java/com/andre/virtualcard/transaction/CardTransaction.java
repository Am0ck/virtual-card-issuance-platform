package com.andre.virtualcard.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "card_transaction")
public class CardTransaction {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_reason", length = 32)
    private DeclineReason declineReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CardTransaction() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getCardId() {
        return cardId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public DeclineReason getDeclineReason() {
        return declineReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
