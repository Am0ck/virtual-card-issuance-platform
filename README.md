# Virtual Card Issuance Platform

## Overview

A backend service for a virtual card platform supporting:

- **Card issuance** — create a virtual card for a cardholder with an initial balance
- **Card retrieval** — current card information and balance
- **Spending** — deduct an amount, declined when funds are insufficient or the card is not active
- **Top-ups** — add funds to an existing active card
- **Transaction history** — every persisted attempt (successful and declined), newest first

All financial operations are idempotent and safe under high concurrency. Correctness is
coordinated by PostgreSQL rather than application memory, so multiple service instances
can operate against the same database.

## Technology

| Component | Version |
|---|---|
| Java | 17 |
| Spring Boot | 4.1 |
| Spring Data JPA (Hibernate) | managed by Boot |
| PostgreSQL | 17 (`postgres:17-alpine`) |
| Flyway | schema migrations |
| Testcontainers | 2.x, integration tests |
| Micrometer / Spring Boot Actuator | metrics & health |
| Docker Compose | local PostgreSQL |

No H2: integration tests run against real PostgreSQL through Testcontainers.

## Running locally

Prerequisites: Java 17 and Docker (with Compose).

```bash
# start local PostgreSQL
docker compose up -d

# Windows
.\mvnw.cmd spring-boot:run

# Unix/macOS
./mvnw spring-boot:run
```

Flyway applies the schema automatically on startup; Hibernate only validates it.
The app listens on port `8080` by default.

- Health: `GET http://localhost:8080/actuator/health`
- Metrics: `GET http://localhost:8080/actuator/metrics`

Run the full test suite (spins up its own throwaway PostgreSQL container; no running
Compose database required):

```bash
.\mvnw.cmd clean verify    # Windows
./mvnw clean verify        # Unix/macOS
```

## API

All mutation endpoints require an `Idempotency-Key` header
(opaque client value, max 128 chars, characters `[A-Za-z0-9._:-]`).

### Create a card

```bash
curl -i -X POST http://localhost:8080/api/v1/cards \
  -H "Idempotency-Key: <client-generated-key>" \
  -H "Content-Type: application/json" \
  -d '{"cardholderName": "Jane Doe", "initialBalance": 100.00}'
```

→ `201 Created`, body `{id, cardholderName, balance, status, createdAt}`,
`Location: /api/v1/cards/{id}`.

### Spend

```bash
curl -i -X POST http://localhost:8080/api/v1/cards/{cardId}/spends \
  -H "Idempotency-Key: <key>" \
  -H "Content-Type: application/json" \
  -d '{"amount": 25.00}'
```

→ `201 Created` with the transaction resource
(`{id, cardId, type, amount, status, createdAt}`).

### Top up

```bash
curl -i -X POST http://localhost:8080/api/v1/cards/{cardId}/top-ups \
  -H "Idempotency-Key: <key>" \
  -H "Content-Type: application/json" \
  -d '{"amount": 50.00}'
```

→ `201 Created` with the transaction resource.

### Retrieve card

```bash
curl -i http://localhost:8080/api/v1/cards/{cardId}
```

→ `200 OK` with the same shape as creation.

### Transaction history

```bash
curl -i "http://localhost:8080/api/v1/cards/{cardId}/transactions?page=0&size=20"
```

→ `200 OK` with `{items: [...], page, size, hasNext}`. Defaults: page 0, size 20,
maximum size 100. Ordered newest first (`created_at DESC, id DESC`). Includes
SUCCESSFUL **and** DECLINED attempts. Existing card without transactions → empty list;
unknown card → 404.

### Errors

Errors use RFC 9457 ProblemDetail (`application/problem+json`) with a stable machine
code and correlation id:

```json
{
  "type": "urn:problem:insufficient-funds",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "The card has insufficient funds for this transaction.",
  "instance": "/api/v1/cards/…",
  "code": "INSUFFICIENT_FUNDS",
  "requestId": "<uuid>"
}
```

| Status | Code |
|---|---|
| 400 | `INVALID_REQUEST` — malformed input/validation/UUID/key |
| 404 | `CARD_NOT_FOUND`, or no API endpoint matches the path (`INVALID_REQUEST`) |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `CARD_BLOCKED` / `CARD_CLOSED` / `IDEMPOTENCY_CONFLICT` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `INSUFFICIENT_FUNDS` |
| 500 | `INTERNAL_ERROR` — sanitized, no internals exposed |

Every response carries `X-Request-Id` (server-generated UUID) which matches the
ProblemDetail `requestId`.

### Idempotent replay semantics

Retrying a mutation with the same key and the same logical payload returns the same
logical result as the original call — including the original HTTP status:

- successful operation → replay returns `201` with the original resource
- declined operation → replay returns the original `422`/`409` decline

A different payload under the same key is rejected with `409 IDEMPOTENCY_CONFLICT`.

## Money handling

- Java `BigDecimal`, PostgreSQL `NUMERIC(19,2)`
- No floating-point money anywhere
- Values requiring rounding are rejected (`20.001` → 400)
- Canonical equivalence: `20`, `20.0`, `20.00`, `20.000`, `2E+1` all represent `20.00`
  and therefore produce identical idempotency fingerprints

## Idempotency

Scope: `(operation_type, resource_id, idempotency_key)` enforced by a single
PostgreSQL constraint:

```sql
UNIQUE NULLS NOT DISTINCT (operation_type, resource_id, idempotency_key)
```

`CREATE_CARD` uses `resource_id = NULL`; spend/top-up scope to their target card, so
the same key may safely be reused across different cards or different operations.

Each request claims its scope atomically in the same transaction as the business work:

```sql
INSERT INTO idempotency_request AS existing (...)
VALUES (...)
ON CONFLICT ON CONSTRAINT uq_idempotency_scope
DO UPDATE SET fingerprint/timestamps = new, results = NULL
WHERE existing.expires_at <= clock_timestamp()
RETURNING id
```

- **Row returned** → this request owns the scope (new claim *or* reclaim of an expired
  row). Proceed with the business operation.
- **No row returned** (existing unexpired scope) → follow-up `SELECT` in the same READ
  COMMITTED transaction: same fingerprint → replay the durable result; changed
  fingerprint → `409 IDEMPOTENCY_CONFLICT`.

The fingerprint is a SHA-256 of the deterministic canonical payload (validated name +
canonical balance for create-card; canonical amount for spend/top-up), never raw JSON.
Concurrent duplicate keys serialize through PostgreSQL's conflict arbitration — exactly
one caller executes the business mutation, everyone else replays it.

Retention defaults to 24 hours (`idempotency.retention-seconds`); expiry is refreshed
from the durable terminal outcome, not request start. A scheduled job deletes expired
rows hourly (`idempotency.cleanup-interval-ms`), but cleanup is housekeeping only —
correctness relies solely on request-time atomic reclamation.

PostgreSQL time (`clock_timestamp()`) governs all expiry decisions because
`now()`/`CURRENT_TIMESTAMP` are fixed at transaction start, while an operation may wait
on locks before completing.

A committed business decline (insufficient funds, blocked, closed) is a durable
idempotent outcome: retrying the same key replays the original decline even if the
balance later changes, until retention expires.

## Concurrency and transaction model

- PostgreSQL is the concurrency authority; there are no JVM-local locks and no JVM state
  that affects correctness.
- Isolation stays at the default READ COMMITTED.
- Mutations load the card through a JPA pessimistic write lock
  (`LockModeType.PESSIMISTIC_WRITE`, i.e. a PostgreSQL row-level lock): mutations to the
  same card serialize, different cards remain fully concurrent, and the second request
  always evaluates against freshly committed state — the balance cannot go below zero.
- Lock ordering is fixed: **idempotency scope first, card row second**, which avoids
  unnecessary card contention from duplicates and prevents lock cycles.
- Reads (card retrieval, history) take no locks.
- Expected business declines persist a DECLINED transaction and commit normally; they
  are mapped to 4xx responses only after commit.
- No optimistic `@Version` in the baseline.

## Data model

Three tables (Flyway-owned):

- `card` — authoritative current balance, `CHECK (balance >= 0)`, status enum
  (`ACTIVE`/`BLOCKED`/`CLOSED`)
- `card_transaction` — one row per persisted attempt; type/status/reason matrix
  enforced by CHECK constraints; FK to card `ON DELETE RESTRICT`
- `idempotency_request` — request-scope metadata + typed result references
  (`result_card_id` / `result_transaction_id`)

History index: `(card_id, created_at DESC, id DESC)`.

This is deliberately **not** a double-entry ledger: `Card.balance` is the source of
truth and transactions are attempt/audit history. That keeps the take-home simple and
efficient while satisfying every required invariant; production evolution would move to
an immutable accounting ledger with derived balances and reconciliation.

## Observability

- Server-generated `X-Request-Id` header on every response; the same UUID flows through
  SLF4J MDC into every log line.
- Structured `card_operation` logs: operation, cardId, transactionId, amount (spend/
  top-up), outcome, declineReason, durationMs, bounded SHA-256 idempotency-key hash,
  replay flag.
- The raw Idempotency-Key, cardholder names, balances, request/response bodies, and
  credentials are never logged.
- Micrometer: `virtual_card.operations{operation,outcome,reason,replay}` counter and
  `virtual_card.operation.duration{operation}` timer — intentionally low-cardinality
  tags (no ids/keys/names).
- AFTER_COMMIT audit events (`audit_event` log lines) demonstrate audit decoupling:
  committed mutations (including declines) are audited after commit; rollbacks and
  idempotent replays are not. Guaranteed external delivery would require an
  outbox/broker design.

### Bonus: asynchronous audit processing

Audit handling is genuinely asynchronous:

- events are published inside the business transaction and dispatched to a dedicated,
  bounded `auditExecutor` (`@EnableAsync`, `ThreadPoolTaskExecutor`: core 2, max 4,
  queue 100, `audit-` thread prefix) only **after commit**
  (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async("auditExecutor")`);
- the request thread does not wait for audit processing to complete — a deliberately
  blocked audit listener does not delay or fail an already-committed request;
- request correlation (`requestId` MDC) is propagated onto the audit worker via a task
  decorator, which clears worker MDC afterwards to prevent cross-task leakage;
- executor saturation **discards** best-effort audit work with a warning (never runs it
  on the request thread, never fails the caller), and listener failures are isolated —
  neither can alter an already-committed financial result.

This remains best-effort by design: guaranteed external audit delivery requires a
transactional outbox + durable broker.

## Card expiration (bonus)

Cards now have a scheduled lifetime:

- **Lifetime**: every newly created card receives `expiresAt = createdAt +
  card.expiration.lifetime` (default `P365D`), derived from the same `createdAt`
  instant used for persistence — no second clock read. PostgreSQL time is NOT used
  to create the timeline; it is authoritative only when deciding whether the
  persisted deadline has been reached (see below).
- **Internal property**: `expiresAt` is persisted on the `card` table but intentionally
  not exposed in API responses; it is a lifecycle detail, not part of the public
  contract. Pre-V2 rows were backfilled in migration V2 with a fixed 365-day duration
  (fixed seconds, matching Java `Duration` semantics); newly created cards use the
  application configuration.
- **State mapping**: expiration transitions `ACTIVE → CLOSED` and `BLOCKED → CLOSED`;
  `CLOSED` remains terminal. No new status or decline reason was introduced — expired
  cards decline with the existing `CARD_CLOSED` semantics, and the decline persists as
  normal history.
- **Scheduler**: a periodic job (`card.expiration.cleanup-interval-ms`, default 60000 ms)
  performs one DB-side bulk update using PostgreSQL `clock_timestamp()` as the
  authoritative clock for deciding whether the persisted deadline has been reached.
  It deliberately bypasses `Card.close()` for efficiency; the SQL
  encodes exactly the same legal transitions. It is safe across multiple instances
  (idempotent predicate + row-level locking).
- **Request-time enforcement**: correctness does NOT depend on scheduler timing. Every
  spend/top-up re-checks expiry after acquiring the pessimistic card row lock, against
  PostgreSQL time (`SELECT clock_timestamp()`) — not the JVM clock. A request that
  waited on the lock past the boundary still declines durably as `CARD_CLOSED`.
- **No financial side effects**: expiration itself creates no transaction row, never
  changes the balance, and never touches history. Only brand-new mutation attempts after
  expiry persist DECLINED attempts.
- **Idempotency unchanged**: an operation that succeeded before expiry replays its
  original successful result even after the card expires; new keys evaluate current
  state and decline.
- **Reads**: GET card/history remain available after expiry and stay read-only.
  The persisted status may lag the expiry boundary by up to one scheduler interval;
  financial correctness is unaffected because mutations enforce expiry synchronously
  after acquiring the card lock.
- **Production evolution**: very large expired-card scans may later be batched and given
  statement/lock timeouts.

## Testing

Integration tests run against real PostgreSQL via Testcontainers with Flyway-applied
schema — no H2, no dependency on any locally running database. Deterministic
coordination uses latches/barriers with finite timeouts, never sleeps.

Highlights:

- two competing spends of 80 on a balance of 100 → exactly one succeeds
- 20 independent concurrent spends of 10 on 100 → exactly 10 succeed, final balance 0
- 20 concurrent same-key requests → one mutation, one shared result
- expired-key concurrent reclamation → exactly one new operation
- durable decline replay survives a later balance change
- missing-card requests leave no retained idempotency claim (rollback proven)
- cleanup deletes only expired rows and leaves card/transaction data intact
- ProblemDetail contract, X-Request-Id correlation, metric deltas

## Design trade-offs

Chosen pragmatically for the assignment scope:

- **Modular monolith** (feature-first packages: card, transaction, idempotency,
  common): strong local transactional requirements, few distributed failure modes,
  simple testing/deployment. Microservices remain a future extraction option.
- **Balance column as source of truth** instead of a double-entry ledger (see Data
  model).
- **Pessimistic locking** over optimistic retries: predictable financial behavior under
  contention without re-evaluation complexity.
- **Durable declines**: declined attempts are first-class history and idempotent
  outcomes.
- **Logical-result replay** instead of byte-for-byte response snapshots: replays return
  the original logical outcome/resource, which avoids storing serialized HTTP bodies.

## Scaling and production evolution

The application is stateless outside PostgreSQL, so instances scale horizontally behind
a load balancer. Not implemented, but clear next steps:

- measured Hikari pool tuning against the PostgreSQL connection budget
- finite row-lock/statement timeouts with retryable `503` mapping for hot cards
- authentication/authorization
- rate limiting at an API gateway
- immutable financial ledger + reconciliation
- transactional outbox → broker for guaranteed external audit delivery
- keyset/cursor pagination for very large histories
- read replicas for read-heavy endpoints
- distributed tracing/APM
- secret and deployment management

## Assumptions

- Single currency (currency is absent from the assignment's data model)
- No authentication/authorization — not part of the assignment
- No public endpoints to block/unblock/close cards; `CLOSED` is a terminal state
- `PENDING` exists in the persistence/domain model but the synchronous flow does not
  produce it (it exists for future asynchronous authorization workflows)
