package com.andre.virtualcard.card;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CardRepository extends JpaRepository<Card, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.id = :cardId")
    Optional<Card> findByIdForUpdate(@Param("cardId") UUID cardId);
}
