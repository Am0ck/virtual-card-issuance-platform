package com.andre.virtualcard.card;

import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardRepositoryPersistenceTest extends AbstractPostgreSQLIntegrationTest {

    @Autowired
    private CardRepository cardRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void savesAndReadsBackACardThroughTheFlywayMigratedSchema() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-23T10:00:00Z");
        Card card = Card.create(id, "Jane Doe", new BigDecimal("100.5"), createdAt);

        cardRepository.saveAndFlush(card);
        entityManager.clear();

        Optional<Card> loaded = cardRepository.findById(id);

        assertTrue(loaded.isPresent());
        assertEquals("Jane Doe", loaded.get().getCardholderName());
        assertEquals(new BigDecimal("100.50"), loaded.get().getBalance());
        assertEquals(CardStatus.ACTIVE, loaded.get().getStatus());
        assertEquals(createdAt, loaded.get().getCreatedAt());
    }

    @Test
    void databaseRejectsNegativeCardBalanceDirectly() {
        // adversarial DB-level check: the CHECK constraint defends the invariant even
        // against raw persistence that bypasses domain validation
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO card (id, cardholder_name, balance, status, created_at) "
                        + "VALUES (?, 'Direct Insert', -0.01, 'ACTIVE', now())",
                UUID.randomUUID()));
    }

    @Test
    void databaseRejectsDuplicateCreateCardIdempotencyScopeWithNullResource() {
        // adversarial DB-level check: UNIQUE NULLS NOT DISTINCT must treat two NULL
        // resource_id rows with the same operation/key as duplicates (CREATE_CARD scope)
        UUID duplicateKeyRow = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO idempotency_request (id, operation_type, resource_id, idempotency_key, "
                        + "request_fingerprint, created_at, expires_at, result_card_id, result_transaction_id) "
                        + "VALUES (?, 'CREATE_CARD', NULL, 'db-scope-key', ?, clock_timestamp(), "
                        + "clock_timestamp() + interval '1 hour', NULL, NULL)",
                UUID.randomUUID(), "f".repeat(64));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO idempotency_request (id, operation_type, resource_id, idempotency_key, "
                        + "request_fingerprint, created_at, expires_at, result_card_id, result_transaction_id) "
                        + "VALUES (?, 'CREATE_CARD', NULL, 'db-scope-key', ?, clock_timestamp(), "
                        + "clock_timestamp() + interval '1 hour', NULL, NULL)",
                duplicateKeyRow, "e".repeat(64)));
    }
}
