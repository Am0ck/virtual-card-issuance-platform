package com.andre.virtualcard.idempotency;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestFingerprint {

    private static final String FIELD_SEPARATOR = "\n";

    private RequestFingerprint() {
    }

    public static String ofCanonicalAmount(BigDecimal canonicalAmount) {
        return sha256("amount=" + canonicalAmount.toPlainString());
    }

    public static String forCardCreation(String normalizedCardholderName, BigDecimal canonicalInitialBalance) {
        return sha256("cardholderName=" + normalizedCardholderName
                + FIELD_SEPARATOR
                + "initialBalance=" + canonicalInitialBalance.toPlainString());
    }

    private static String sha256(String canonicalRepresentation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalRepresentation.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
