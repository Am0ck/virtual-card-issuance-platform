package com.andre.virtualcard.card;

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
@Table(name = "card")
public class Card {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "cardholder_name", nullable = false, length = 100)
    private String cardholderName;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CardStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Card() {
    }

    public UUID getId() {
        return id;
    }

    public String getCardholderName() {
        return cardholderName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public CardStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
