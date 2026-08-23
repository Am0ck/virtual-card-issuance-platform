package com.andre.virtualcard.card;

import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardRepositoryPersistenceTest extends AbstractPostgreSQLIntegrationTest {

    @Autowired
    private CardRepository cardRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void savesAndReadsBackACardThroughTheFlywayMigratedSchema() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-23T10:00:00Z");
        Card card = Card.create(id, "Andre Cassar Mockridge", new BigDecimal("100.5"), createdAt);

        cardRepository.saveAndFlush(card);
        entityManager.clear();

        Optional<Card> loaded = cardRepository.findById(id);

        assertTrue(loaded.isPresent());
        assertEquals("Andre Cassar Mockridge", loaded.get().getCardholderName());
        assertEquals(new BigDecimal("100.50"), loaded.get().getBalance());
        assertEquals(CardStatus.ACTIVE, loaded.get().getStatus());
        assertEquals(createdAt, loaded.get().getCreatedAt());
    }
}
