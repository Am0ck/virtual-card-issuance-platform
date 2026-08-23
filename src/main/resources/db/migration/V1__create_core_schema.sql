CREATE TABLE card (
    id UUID PRIMARY KEY,
    cardholder_name VARCHAR(100) NOT NULL,
    balance NUMERIC(19, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_card_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_card_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED'))
);

CREATE TABLE card_transaction (
    id UUID PRIMARY KEY,
    card_id UUID NOT NULL,
    type VARCHAR(32) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    decline_reason VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_card_transaction_card
        FOREIGN KEY (card_id)
        REFERENCES card(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_card_transaction_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_card_transaction_type CHECK (type IN ('INITIAL_FUNDING', 'TOP_UP', 'SPEND')),
    CONSTRAINT chk_card_transaction_status CHECK (status IN ('SUCCESSFUL', 'DECLINED', 'PENDING')),
    CONSTRAINT chk_card_transaction_decline_reason CHECK (
        decline_reason IS NULL
        OR decline_reason IN ('INSUFFICIENT_FUNDS', 'CARD_BLOCKED', 'CARD_CLOSED')
    ),
    CONSTRAINT chk_card_transaction_status_reason CHECK (
        (status = 'DECLINED' AND decline_reason IS NOT NULL)
        OR (status <> 'DECLINED' AND decline_reason IS NULL)
    ),
    CONSTRAINT chk_card_transaction_type_status_reason CHECK (
        (type = 'INITIAL_FUNDING' AND status = 'SUCCESSFUL' AND decline_reason IS NULL)
        OR (type = 'SPEND' AND status IN ('SUCCESSFUL', 'PENDING') AND decline_reason IS NULL)
        OR (type = 'SPEND'
            AND status = 'DECLINED'
            AND decline_reason IN ('INSUFFICIENT_FUNDS', 'CARD_BLOCKED', 'CARD_CLOSED'))
        OR (type = 'TOP_UP' AND status IN ('SUCCESSFUL', 'PENDING') AND decline_reason IS NULL)
        OR (type = 'TOP_UP'
            AND status = 'DECLINED'
            AND decline_reason IN ('CARD_BLOCKED', 'CARD_CLOSED'))
    )
);

CREATE INDEX idx_card_transaction_history
    ON card_transaction(card_id, created_at DESC, id DESC);

CREATE TABLE idempotency_request (
    id UUID PRIMARY KEY,
    operation_type VARCHAR(32) NOT NULL,
    resource_id UUID,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    result_card_id UUID,
    result_transaction_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_scope
        UNIQUE NULLS NOT DISTINCT (operation_type, resource_id, idempotency_key),
    CONSTRAINT fk_idempotency_request_result_card
        FOREIGN KEY (result_card_id)
        REFERENCES card(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_idempotency_request_result_transaction
        FOREIGN KEY (result_transaction_id)
        REFERENCES card_transaction(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_idempotency_request_operation_type CHECK (
        operation_type IN ('CREATE_CARD', 'SPEND', 'TOP_UP')
    ),
    CONSTRAINT chk_idempotency_request_fingerprint_length CHECK (
        length(request_fingerprint) = 64
    ),
    CONSTRAINT chk_idempotency_request_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_idempotency_request_result_integrity CHECK (
        (result_card_id IS NULL AND result_transaction_id IS NULL)
        OR (operation_type = 'CREATE_CARD'
            AND result_card_id IS NOT NULL
            AND result_transaction_id IS NULL)
        OR (operation_type IN ('SPEND', 'TOP_UP')
            AND result_card_id IS NULL
            AND result_transaction_id IS NOT NULL)
    )
);

CREATE INDEX idx_idempotency_request_expires_at
    ON idempotency_request(expires_at);
