package com.andre.virtualcard.idempotency;

import com.andre.virtualcard.idempotency.IdempotencyClaim.Claimed;
import com.andre.virtualcard.idempotency.IdempotencyClaim.Replayed;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class IdempotencyService {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final int MAX_KEY_LENGTH = 128;

    private static final String CLAIM_SQL = """
            INSERT INTO idempotency_request AS existing (
                id, operation_type, resource_id, idempotency_key,
                request_fingerprint, created_at, expires_at,
                result_card_id, result_transaction_id
            ) VALUES (
                ?, ?, ?, ?,
                ?, clock_timestamp(), clock_timestamp() + make_interval(secs => ?),
                NULL, NULL
            )
            ON CONFLICT ON CONSTRAINT uq_idempotency_scope
            DO UPDATE SET
                request_fingerprint = EXCLUDED.request_fingerprint,
                created_at = clock_timestamp(),
                expires_at = clock_timestamp() + make_interval(secs => ?),
                result_card_id = NULL,
                result_transaction_id = NULL
            WHERE existing.expires_at <= clock_timestamp()
            RETURNING id
            """;

    private static final String COMPLETE_SQL = """
            UPDATE idempotency_request
            SET result_card_id = ?,
                result_transaction_id = ?,
                expires_at = clock_timestamp() + make_interval(secs => ?)
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyRepository idempotencyRepository;

    @Value("${idempotency.retention-seconds:86400}")
    private long retentionSeconds;

    public IdempotencyService(JdbcTemplate jdbcTemplate, IdempotencyRepository idempotencyRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyRepository = idempotencyRepository;
    }

    public IdempotencyClaim claim(IdempotencyOperation operation, UUID resourceId, String key, String fingerprint) {
        validateKey(key);
        UUID candidateClaimId = UUID.randomUUID();
        // The returned id is authoritative: for a brand-new scope it is the candidate id,
        // for a reclaimed expired scope it is the existing row's surrogate id.
        List<UUID> returned = jdbcTemplate.query(
                CLAIM_SQL,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                candidateClaimId,
                operation.name(),
                resourceId,
                key,
                fingerprint,
                retentionSeconds,
                retentionSeconds
        );
        if (!returned.isEmpty()) {
            return new Claimed(returned.get(0));
        }
        return replayOrConflict(operation, resourceId, key, fingerprint);
    }

    public void complete(UUID claimId, UUID resultCardId, UUID resultTransactionId) {
        int updated = jdbcTemplate.update(COMPLETE_SQL, resultCardId, resultTransactionId, retentionSeconds, claimId);
        if (updated != 1) {
            throw new IllegalStateException("Expected to finalize exactly one idempotency claim " + claimId);
        }
    }

    private IdempotencyClaim replayOrConflict(
            IdempotencyOperation operation,
            UUID resourceId,
            String key,
            String fingerprint
    ) {
        IdempotencyRequest existing = idempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(operation, resourceId, key)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency claim conflict resolved but no committed row was found for scope ("
                                + operation + ", " + resourceId + ", " + key + ")"
                ));
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(operation, resourceId, key);
        }
        return new Replayed(existing);
    }

    private void validateKey(String key) {
        Objects.requireNonNull(key, "Idempotency-Key must not be null");
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 128 characters");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain only A-Z, a-z, 0-9, '-', '_', '.' and ':'");
        }
    }
}
