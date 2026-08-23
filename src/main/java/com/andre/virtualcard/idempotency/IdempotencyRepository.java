package com.andre.virtualcard.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRequest, UUID> {

    Optional<IdempotencyRequest> findByOperationTypeAndResourceIdAndIdempotencyKey(
            IdempotencyOperation operationType,
            UUID resourceId,
            String idempotencyKey
    );
}
