package com.andre.virtualcard.card;

import com.andre.virtualcard.card.CardOperationResult.Declined;
import com.andre.virtualcard.card.CardOperationResult.Successful;
import com.andre.virtualcard.transaction.DeclineReason;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardTest {

    private static final UUID CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-23T10:00:00Z");

    private static Card activeCard(String initialBalance) {
        return Card.create(CARD_ID, "Andre Cassar Mockridge", new BigDecimal(initialBalance), CREATED_AT);
    }

    @Nested
    class Creation {

        @Test
        void createsActiveCardWithTrimmedNameAndGivenBalance() {
            Card card = Card.create(
                    CARD_ID, "  Andre Cassar Mockridge  ", new BigDecimal("100.5"), CREATED_AT);

            assertEquals(CARD_ID, card.getId());
            assertEquals("Andre Cassar Mockridge", card.getCardholderName());
            assertEquals(new BigDecimal("100.50"), card.getBalance());
            assertEquals(CardStatus.ACTIVE, card.getStatus());
            assertEquals(CREATED_AT, card.getCreatedAt());
        }

        @Test
        void acceptsZeroInitialBalance() {
            Card card = activeCard("0");

            assertEquals(new BigDecimal("0.00"), card.getBalance());
        }

        @Test
        void rejectsNegativeInitialBalance() {
            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, "Andre", new BigDecimal("-0.01"), CREATED_AT));
        }

        @Test
        void rejectsInitialBalanceRequiringRounding() {
            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, "Andre", new BigDecimal("10.123"), CREATED_AT));
        }

        @Test
        void rejectsMissingIdentityOrName() {
            assertThrows(NullPointerException.class,
                    () -> Card.create(null, "Andre", BigDecimal.TEN, CREATED_AT));
            assertThrows(NullPointerException.class,
                    () -> Card.create(CARD_ID, null, BigDecimal.TEN, CREATED_AT));
            assertThrows(NullPointerException.class,
                    () -> Card.create(CARD_ID, "Andre", BigDecimal.TEN, null));
        }

        @Test
        void rejectsBlankOrOverlongCardholderName() {
            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, "   ", BigDecimal.TEN, CREATED_AT));
            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, "A".repeat(101), BigDecimal.TEN, CREATED_AT));
        }

        @Test
        void acceptsUnicodeNamesWithLettersSpacesAndCommonPunctuation() {
            String[] validNames = {"José García", "Anne-Marie O'Connor", "Zoë Smith"};

            for (String name : validNames) {
                Card card = Card.create(CARD_ID, name, BigDecimal.TEN, CREATED_AT);
                assertEquals(name, card.getCardholderName());
            }
        }

        @Test
        void rejectsNumericalDigitsIncludingNonAsciiDecimals() {
            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, "Andre123", BigDecimal.TEN, CREATED_AT));
            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, "Andre\u0662", BigDecimal.TEN, CREATED_AT));
        }

        @Test
        void rejectsControlCharactersBeforeNormalization() {
            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, "Andre\nMockridge", BigDecimal.TEN, CREATED_AT));
            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, "Andre\tMockridge", BigDecimal.TEN, CREATED_AT));
        }

        @Test
        void preservesInternalSpacingCaseAndPunctuation() {
            Card card = Card.create(CARD_ID, "  anne-marie  o'connor  ", BigDecimal.TEN, CREATED_AT);

            assertEquals("anne-marie  o'connor", card.getCardholderName());
        }

        @Test
        void enforcesNameLimitInCodePointsNotUtf16Units() {
            String deseretLetter = "\uD801\uDC00";
            String overLimitInCodePoints = deseretLetter.repeat(100) + "A";

            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, overLimitInCodePoints, BigDecimal.TEN, CREATED_AT));

            String withinLimitInCodePointsButOverUtf16Units = deseretLetter.repeat(50) + "A";
            Card card = Card.create(
                    CARD_ID, withinLimitInCodePointsButOverUtf16Units, BigDecimal.TEN, CREATED_AT);
            assertEquals(withinLimitInCodePointsButOverUtf16Units, card.getCardholderName());
        }
    }

    @Nested
    class Spend {

        @Test
        void successfulSpendMutatesBalance() {
            Card card = activeCard("100");

            CardOperationResult result = card.spend(new BigDecimal("25"));

            assertInstanceOf(Successful.class, result);
            assertEquals(new BigDecimal("75.00"), card.getBalance());
        }

        @Test
        void spendOfExactBalanceLeavesZero() {
            Card card = activeCard("80.25");

            CardOperationResult result = card.spend(new BigDecimal("80.25"));

            assertInstanceOf(Successful.class, result);
            assertEquals(new BigDecimal("0.00"), card.getBalance());
        }

        @Test
        void insufficientFundsDeclinesWithoutChangingBalance() {
            Card card = activeCard("20");

            CardOperationResult result = card.spend(new BigDecimal("50"));

            Declined declined = assertInstanceOf(Declined.class, result);
            assertEquals(DeclineReason.INSUFFICIENT_FUNDS, declined.reason());
            assertEquals(new BigDecimal("20.00"), card.getBalance());
        }

        @Test
        void blockedCardSpendsAreDeclinedAsBlocked() {
            Card card = activeCard("100");
            card.block();

            CardOperationResult result = card.spend(BigDecimal.TEN);

            Declined declined = assertInstanceOf(Declined.class, result);
            assertEquals(DeclineReason.CARD_BLOCKED, declined.reason());
            assertEquals(new BigDecimal("100.00"), card.getBalance());
        }

        @Test
        void closedCardSpendsAreDeclinedAsClosed() {
            Card card = activeCard("100");
            card.close();

            CardOperationResult result = card.spend(BigDecimal.TEN);

            Declined declined = assertInstanceOf(Declined.class, result);
            assertEquals(DeclineReason.CARD_CLOSED, declined.reason());
            assertEquals(new BigDecimal("100.00"), card.getBalance());
        }

        @Test
        void rejectsInvalidAmountsBeforeBusinessEvaluation() {
            Card card = activeCard("100");

            assertThrows(NullPointerException.class, () -> card.spend(null));
            assertThrows(IllegalArgumentException.class, () -> card.spend(BigDecimal.ZERO));
            assertThrows(IllegalArgumentException.class, () -> card.spend(new BigDecimal("-1.00")));
            assertThrows(IllegalArgumentException.class, () -> card.spend(new BigDecimal("10.123")));
        }

        @Test
        void invalidAmountOnInactiveCardIsRejectedRatherThanDeclined() {
            Card blocked = activeCard("100");
            blocked.block();

            assertThrows(IllegalArgumentException.class, () -> blocked.spend(BigDecimal.ZERO));
        }
    }

    @Nested
    class TopUp {

        private static final String MAX_BALANCE = "99999999999999999.99";

        @Test
        void successfulTopUpIncreasesBalance() {
            Card card = activeCard("40");

            CardOperationResult result = card.topUp(new BigDecimal("10.5"));

            assertInstanceOf(Successful.class, result);
            assertEquals(new BigDecimal("50.50"), card.getBalance());
        }

        @Test
        void topUpOverflowingNumericColumnIsRejectedAndBalanceUnchanged() {
            Card card = activeCard(MAX_BALANCE);

            assertThrows(IllegalArgumentException.class, () -> card.topUp(new BigDecimal("0.01")));
            assertEquals(new BigDecimal(MAX_BALANCE), card.getBalance());
        }

        @Test
        void cardCanBeCreatedWithMaximumRepresentableBalanceAndSpentFrom() {
            Card card = activeCard(MAX_BALANCE);

            CardOperationResult result = card.spend(new BigDecimal("0.01"));

            assertInstanceOf(Successful.class, result);
            assertEquals(new BigDecimal("99999999999999999.98"), card.getBalance());
        }

        @Test
        void rejectsInitialBalanceExceedingNumericRange() {
            assertThrows(IllegalArgumentException.class,
                    () -> Card.create(CARD_ID, "Andre", new BigDecimal("100000000000000000.00"), CREATED_AT));
        }

        @Test
        void canonicalizesEquivalentRepresentationsOfTheSameValue() {
            Card card = activeCard("10");

            card.topUp(new BigDecimal("20"));
            card.topUp(new BigDecimal("2E+1"));

            assertEquals(new BigDecimal("50.00"), card.getBalance());
        }

        @Test
        void blockedCardTopUpsAreDeclinedAsBlocked() {
            Card card = activeCard("40");
            card.block();

            CardOperationResult result = card.topUp(BigDecimal.TEN);

            Declined declined = assertInstanceOf(Declined.class, result);
            assertEquals(DeclineReason.CARD_BLOCKED, declined.reason());
            assertEquals(new BigDecimal("40.00"), card.getBalance());
        }

        @Test
        void closedCardTopUpsAreDeclinedAsClosed() {
            Card card = activeCard("40");
            card.close();

            CardOperationResult result = card.topUp(BigDecimal.TEN);

            Declined declined = assertInstanceOf(Declined.class, result);
            assertEquals(DeclineReason.CARD_CLOSED, declined.reason());
            assertEquals(new BigDecimal("40.00"), card.getBalance());
        }

        @Test
        void rejectsInvalidAmounts() {
            Card card = activeCard("40");

            assertThrows(NullPointerException.class, () -> card.topUp(null));
            assertThrows(IllegalArgumentException.class, () -> card.topUp(BigDecimal.ZERO));
            assertThrows(IllegalArgumentException.class, () -> card.topUp(new BigDecimal("-5.00")));
            assertThrows(IllegalArgumentException.class, () -> card.topUp(new BigDecimal("5.001")));
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void activeCardCanBeBlockedAndActivatedAgain() {
            Card card = activeCard("10");

            card.block();
            assertEquals(CardStatus.BLOCKED, card.getStatus());

            card.activate();
            assertEquals(CardStatus.ACTIVE, card.getStatus());
        }

        @Test
        void activeAndBlockedCardsCanBeClosed() {
            Card fromActive = activeCard("10");
            fromActive.close();
            assertEquals(CardStatus.CLOSED, fromActive.getStatus());

            Card fromBlocked = activeCard("10");
            fromBlocked.block();
            fromBlocked.close();
            assertEquals(CardStatus.CLOSED, fromBlocked.getStatus());
        }

        @Test
        void closedCardIsTerminal() {
            Card card = activeCard("10");
            card.close();

            assertThrows(IllegalStateException.class, card::block);
            assertThrows(IllegalStateException.class, card::activate);
            assertThrows(IllegalStateException.class, card::close);
            assertEquals(CardStatus.CLOSED, card.getStatus());
        }

        @Test
        void onlyBlockedCardsCanBeActivated() {
            Card card = activeCard("10");

            assertThrows(IllegalStateException.class, card::activate);

            card.block();
            card.activate();
            assertEquals(CardStatus.ACTIVE, card.getStatus());
        }

        @Test
        void reactivatedCardOperatesNormally() {
            Card card = activeCard("10");
            card.block();
            card.activate();

            CardOperationResult result = card.spend(new BigDecimal("4"));

            assertInstanceOf(Successful.class, result);
            assertEquals(new BigDecimal("6.00"), card.getBalance());
        }
    }
}
