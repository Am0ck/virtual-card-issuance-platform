package com.andre.virtualcard.card;

import com.andre.virtualcard.common.MonetaryAmounts;
import com.andre.virtualcard.transaction.DeclineReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "card")
public class Card {

    private static final int CARDHOLDER_NAME_MAX_LENGTH = 100;

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

    private Card(UUID id, String cardholderName, BigDecimal balance, CardStatus status, Instant createdAt) {
        this.id = id;
        this.cardholderName = cardholderName;
        this.balance = balance;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Card create(UUID id, String cardholderName, BigDecimal initialBalance, Instant createdAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        String normalizedName = requireCardholderName(cardholderName);
        BigDecimal balance = MonetaryAmounts.requireNonNegative(initialBalance, "initialBalance");
        return new Card(id, normalizedName, balance, CardStatus.ACTIVE, createdAt);
    }

    public CardOperationResult spend(BigDecimal amount) {
        BigDecimal canonicalAmount = MonetaryAmounts.requirePositive(amount, "amount");
        DeclineReason statusDecline = declineForInactiveCard();
        if (statusDecline != null) {
            return CardOperationResult.declined(statusDecline);
        }
        if (balance.compareTo(canonicalAmount) < 0) {
            return CardOperationResult.declined(DeclineReason.INSUFFICIENT_FUNDS);
        }
        balance = balance.subtract(canonicalAmount);
        return CardOperationResult.successful();
    }

    public CardOperationResult topUp(BigDecimal amount) {
        BigDecimal canonicalAmount = MonetaryAmounts.requirePositive(amount, "amount");
        DeclineReason statusDecline = declineForInactiveCard();
        if (statusDecline != null) {
            return CardOperationResult.declined(statusDecline);
        }
        BigDecimal updatedBalance = MonetaryAmounts
                .requireWithinNumericRange(balance.add(canonicalAmount), "resulting balance");
        balance = updatedBalance;
        return CardOperationResult.successful();
    }

    public void block() {
        if (status != CardStatus.ACTIVE) {
            throw new IllegalStateException("Only an ACTIVE card can be blocked");
        }
        status = CardStatus.BLOCKED;
    }

    public void activate() {
        if (status != CardStatus.BLOCKED) {
            throw new IllegalStateException("Only a BLOCKED card can be activated");
        }
        status = CardStatus.ACTIVE;
    }

    public void close() {
        if (status == CardStatus.CLOSED) {
            throw new IllegalStateException("A CLOSED card is terminal");
        }
        status = CardStatus.CLOSED;
    }

    private DeclineReason declineForInactiveCard() {
        if (status == CardStatus.BLOCKED) {
            return DeclineReason.CARD_BLOCKED;
        }
        if (status == CardStatus.CLOSED) {
            return DeclineReason.CARD_CLOSED;
        }
        return null;
    }

    private static String requireCardholderName(String cardholderName) {
        Objects.requireNonNull(cardholderName, "cardholderName must not be null");
        requireNoForbiddenCodePoints(cardholderName);
        String normalized = cardholderName.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("cardholderName must not be blank");
        }
        long lengthInCodePoints = normalized.codePoints().count();
        if (lengthInCodePoints > CARDHOLDER_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "cardholderName must not exceed " + CARDHOLDER_NAME_MAX_LENGTH + " characters"
            );
        }
        return normalized;
    }

    private static void requireNoForbiddenCodePoints(String value) {
        value.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.FORMAT) {
                throw new IllegalArgumentException(
                        "cardholderName must not contain control or format characters: U+"
                                + Integer.toHexString(codePoint).toUpperCase()
                );
            }
            if (type == Character.DECIMAL_DIGIT_NUMBER) {
                throw new IllegalArgumentException(
                        "cardholderName must not contain numerical digits: U+"
                                + Integer.toHexString(codePoint).toUpperCase()
                );
            }
        });
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
