package com.andre.virtualcard.common.error;

public enum ApiErrorCode {
    INVALID_REQUEST("Invalid request"),
    CARD_NOT_FOUND("Card not found"),
    CARD_BLOCKED("Card blocked"),
    CARD_CLOSED("Card closed"),
    INSUFFICIENT_FUNDS("Insufficient funds"),
    IDEMPOTENCY_CONFLICT("Idempotency conflict"),
    METHOD_NOT_ALLOWED("Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE("Unsupported media type"),
    INTERNAL_ERROR("Internal error");

    private final String title;

    ApiErrorCode(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public String typeSlug() {
        return name().toLowerCase().replace('_', '-');
    }
}
