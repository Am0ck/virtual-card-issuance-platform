-- Card expiration (bonus feature).
--
-- Every card receives a deterministic expiry. The backfill below applies a FIXED
-- default of exactly 365 days (= 31,536,000 seconds, matching Java Duration
-- semantics rather than calendar days) measured from each card's creation time.
-- This fixed value applies ONLY to pre-V2 rows; newly created cards derive their
-- expires_at from application configuration (card.expiration.lifetime) at
-- creation time.
-- Pre-V2 rows that are already past their backfilled expiry are closed by the
-- scheduled expiration job on its first run.
ALTER TABLE card ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE card SET expires_at = created_at + INTERVAL '31536000 seconds';

ALTER TABLE card ALTER COLUMN expires_at SET NOT NULL;

-- DB-level mirror of the domain invariant expiresAt > createdAt (Card.create),
-- preserving it even against raw persistence that bypasses domain validation.
ALTER TABLE card
    ADD CONSTRAINT chk_card_expiry_after_creation
    CHECK (expires_at > created_at);

-- Serves the scheduled bulk close and any expiry-aware lookups for cards that can
-- still transition to CLOSED.
CREATE INDEX idx_card_expires_at_open
    ON card(expires_at)
    WHERE status IN ('ACTIVE', 'BLOCKED');
