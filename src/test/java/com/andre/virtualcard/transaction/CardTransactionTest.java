package com.andre.virtualcard.transaction;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardTransactionTest {

    private static final UUID TXN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-23T10:00:00Z");

    @Nested
    class FactoryOutcomes {

        @Test
        void initialFundingProducesSuccessfulInitialFundingWithoutReason() {
            CardTransaction txn = CardTransaction.initialFunding(TXN_ID, CARD_ID, new BigDecimal("100"), CREATED_AT);

            assertEquals(TransactionType.INITIAL_FUNDING, txn.getType());
            assertEquals(TransactionStatus.SUCCESSFUL, txn.getStatus());
            assertNull(txn.getDeclineReason());
            assertEquals(new BigDecimal("100.00"), txn.getAmount());
        }

        @Test
        void successfulSpendProducesSuccessfulSpendWithoutReason() {
            CardTransaction txn = CardTransaction.successfulSpend(TXN_ID, CARD_ID, new BigDecimal("25.5"), CREATED_AT);

            assertEquals(TransactionType.SPEND, txn.getType());
            assertEquals(TransactionStatus.SUCCESSFUL, txn.getStatus());
            assertNull(txn.getDeclineReason());
            assertEquals(new BigDecimal("25.50"), txn.getAmount());
        }

        @Test
        void successfulTopUpProducesSuccessfulTopUpWithoutReason() {
            CardTransaction txn = CardTransaction.successfulTopUp(TXN_ID, CARD_ID, new BigDecimal("10"), CREATED_AT);

            assertEquals(TransactionType.TOP_UP, txn.getType());
            assertEquals(TransactionStatus.SUCCESSFUL, txn.getStatus());
            assertNull(txn.getDeclineReason());
        }

        @Test
        void declinedSpendSupportsAllSpendDeclineReasons() {
            for (DeclineReason reason : DeclineReason.values()) {
                CardTransaction txn = CardTransaction.declinedSpend(
                        TXN_ID, CARD_ID, new BigDecimal("50"), reason, CREATED_AT);

                assertEquals(TransactionType.SPEND, txn.getType());
                assertEquals(TransactionStatus.DECLINED, txn.getStatus());
                assertEquals(reason, txn.getDeclineReason());
            }
        }

        @Test
        void declinedTopUpRejectsInsufficientFundsReason() {
            assertThrows(IllegalArgumentException.class, () -> CardTransaction.declinedTopUp(
                    TXN_ID, CARD_ID, new BigDecimal("50"), DeclineReason.INSUFFICIENT_FUNDS, CREATED_AT));
        }

        @Test
        void declinedTopUpProducesDeclinedTopUpWithAllowedReasons() {
            for (DeclineReason reason : new DeclineReason[]{DeclineReason.CARD_BLOCKED, DeclineReason.CARD_CLOSED}) {
                CardTransaction txn = CardTransaction.declinedTopUp(
                        TXN_ID, CARD_ID, new BigDecimal("50"), reason, CREATED_AT);

                assertEquals(TransactionType.TOP_UP, txn.getType());
                assertEquals(TransactionStatus.DECLINED, txn.getStatus());
                assertEquals(reason, txn.getDeclineReason());
            }
        }

        @Test
        void transactionsExposeSuppliedIdentityAndTimestamp() {
            CardTransaction txn = CardTransaction.successfulSpend(TXN_ID, CARD_ID, BigDecimal.ONE, CREATED_AT);

            assertEquals(TXN_ID, txn.getId());
            assertEquals(CARD_ID, txn.getCardId());
            assertEquals(CREATED_AT, txn.getCreatedAt());
        }
    }

    @Nested
    class FactoryValidation {

        @Test
        void rejectsNullIdentityFields() {
            BigDecimal amount = BigDecimal.TEN;

            assertThrows(NullPointerException.class,
                    () -> CardTransaction.initialFunding(null, CARD_ID, amount, CREATED_AT));
            assertThrows(NullPointerException.class,
                    () -> CardTransaction.successfulSpend(TXN_ID, null, amount, CREATED_AT));
            assertThrows(NullPointerException.class,
                    () -> CardTransaction.successfulTopUp(TXN_ID, CARD_ID, amount, null));
            assertThrows(NullPointerException.class,
                    () -> CardTransaction.declinedSpend(TXN_ID, CARD_ID, amount, null, CREATED_AT));
            assertThrows(NullPointerException.class,
                    () -> CardTransaction.declinedTopUp(TXN_ID, CARD_ID, amount, null, CREATED_AT));
        }

        @Test
        void rejectsInvalidAmounts() {
            assertThrows(NullPointerException.class,
                    () -> CardTransaction.successfulSpend(TXN_ID, CARD_ID, null, CREATED_AT));
            assertThrows(IllegalArgumentException.class,
                    () -> CardTransaction.initialFunding(TXN_ID, CARD_ID, BigDecimal.ZERO, CREATED_AT));
            assertThrows(IllegalArgumentException.class,
                    () -> CardTransaction.successfulTopUp(TXN_ID, CARD_ID, new BigDecimal("-1.00"), CREATED_AT));
            assertThrows(IllegalArgumentException.class,
                    () -> CardTransaction.declinedSpend(TXN_ID, CARD_ID, new BigDecimal("10.123"),
                            DeclineReason.INSUFFICIENT_FUNDS, CREATED_AT));
            assertThrows(IllegalArgumentException.class,
                    () -> CardTransaction.declinedTopUp(TXN_ID, CARD_ID, new BigDecimal("0.001"),
                            DeclineReason.CARD_BLOCKED, CREATED_AT));
        }
    }
}
