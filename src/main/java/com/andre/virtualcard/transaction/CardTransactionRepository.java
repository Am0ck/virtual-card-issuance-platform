package com.andre.virtualcard.transaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CardTransactionRepository extends JpaRepository<CardTransaction, UUID> {

    Slice<CardTransaction> findByCardIdOrderByCreatedAtDescIdDesc(UUID cardId, Pageable pageable);
}
