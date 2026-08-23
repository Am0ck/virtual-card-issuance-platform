package com.andre.virtualcard.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way, bounded representation of an Idempotency-Key for logs/correlation only.
 * Never persisted and never used for arbitration; distinct from request fingerprints.
 */
public final class IdempotencyKeyHasher {

    private static final int BOUNDED_HEX_LENGTH = 12;

    private IdempotencyKeyHasher() {
    }

    public static String hash(String idempotencyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String fullHex = HexFormat.of().formatHex(
                    digest.digest(idempotencyKey.getBytes(StandardCharsets.UTF_8)));
            return fullHex.substring(0, BOUNDED_HEX_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
