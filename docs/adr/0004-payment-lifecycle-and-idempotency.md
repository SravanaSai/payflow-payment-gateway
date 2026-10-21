# ADR-0004: Payment Lifecycle, Tenant Isolation, and Idempotency

## Status

Accepted

## Context

Phase 2 adds `payment-service` (port 8082). Payments move money, so three failures are unacceptable: a retried request charging twice, a concurrent request silently overwriting another's change, and one merchant — or one environment — seeing another's payments.

Requests reach payment-service through the edge gateway, which authenticates the API key (ADR-0001) and stamps the caller's identity as `X-PayFlow-*` headers.

## Decision

### Payment state machine

`PENDING → AUTHORIZED → CAPTURED → REFUNDED`, plus `PENDING → FAILED` and `PENDING | AUTHORIZED → CANCELLED`. The legal transitions live in one table on `PaymentStatus`, and every command goes through `Payment.transitionTo`, so no code path can skip a guard.

Each transition is its own endpoint (`/authorize`, `/capture`, `/cancel`) rather than a `PATCH` of the status field: each has its own inputs, validation, and idempotency record. `REFUNDED` means a full refund until the Refund aggregate arrives in Phase 5.

### Tenant isolation

Every payment belongs to a `PaymentOwner`: one merchant in one environment. The repository port has no unscoped lookup. Another owner's payment is a 404, never a 403, so its existence is not leaked. A TEST key can never read or move a LIVE payment.

### Concurrency

JPA `@Version` optimistic locking. The persistence adapter translates a lost update into `CONCURRENT_MODIFICATION` (409). The application service keeps Spring's default rollback rules: `noRollbackFor = PayFlowException`, as merchant-service uses, would turn that 409 into a 500, because the failed flush has already marked the transaction rollback-only.

### Idempotency

Every `POST` under `/v1/payments` requires an `Idempotency-Key`, scoped to merchant, environment, and key, and kept for 24 hours.

- The key is claimed by one atomic `INSERT ... ON CONFLICT` in its own transaction, before the payment work runs. A duplicate that arrives while the first is in flight gets 409 `IDEMPOTENCY_IN_PROGRESS`.
- The request fingerprint is SHA-256 over the method, path, and exact body bytes. Reusing a key for a different request is 409 `IDEMPOTENCY_CONFLICT`.
- Every response below 500 except 409 is stored and replayed with `Idempotency-Replayed: true`. A 409 or 5xx releases the key so a retry can run.
- Postgres is the source of truth. Redis is a read-through replay cache (a Decorator around the Postgres store): the first retry of a key reads Postgres and caches the completed record, and later retries are answered from Redis, including the claim, so a retry storm reaches Postgres once per key. Only `COMPLETED` records are cached, each for at most 10 minutes and never longer than its remaining life in Postgres. Any Redis error counts as a miss.
- Expired records are deleted by a scheduled purge, every 15 minutes, in batches of 1000.

### Gateway trust boundary

- The gateway authenticates every `/v1/**` path by default. Only `POST /v1/merchants` is public, because the caller has no key until it returns.
- The gateway removes client-supplied `X-PayFlow-*` headers from every request before any other check, so any such header seen downstream was stamped by the gateway.
- payment-service rejects `/v1/**` requests without identity headers (401) and trusts them only because it is reachable solely through the gateway.

## Consequences

### Positive

- A retried request never creates a second payment, and races resolve deterministically inside Postgres.
- Lost updates surface as 409 instead of silent overwrites.
- New gateway routes are authenticated automatically, and a forged identity can neither reach a service nor spend another merchant's rate-limit quota.

### Negative

- A crash between committing a payment and recording its response leaves that key `IN_PROGRESS` until it expires. It is never re-run, which is safe, but the key answers 409 for up to 24 hours.
- Stored 4xx responses use up their key: a client fixing a rejected request must send it with a new key, and a stored 422 replays even after the payment has changed.
- Every authenticated request costs one lookup call from the gateway to merchant-service.
- While Redis is down, every cache call waits up to its 200 ms timeout before falling back to Postgres until the idempotency-cache circuit breaker opens; see ADR-0005.
- Anonymous callers get 401 rather than 404 for unknown `/v1` paths.

### Operational

- payment-service must never be reachable from outside the private network: its identity headers prove presence, not authenticity. Where that cannot be guaranteed, add mutual TLS or gateway-signed identity headers.
- Watch `payflow_idempotency_cache_total{result="error"}`: sustained growth means Redis is down and every replay is falling back to Postgres. `payflow_idempotency_requests_total` breaks requests down by outcome, and `payflow_idempotency_purged_total` confirms the purge is running.
- Every payment-service instance runs the purge; that is harmless because deletes are idempotent. ShedLock (Phase 9) can restrict it to one instance.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Claim the key inside the payment transaction | No crash window, but duplicates would block holding database connections, and a rolled-back claim could not store a failed request's response |
| Redis as the idempotency source of truth | Can lose writes on failover; a lost claim means a double charge |
| Write-through cache on completion | Even the first retry would hit Redis, but the store interface and the filter would have to change |
| Read-through cache without the claim short-circuit | Every retry would still run the claim upsert in Postgres, so a retry storm still reaches the database |
| Partition the table by day and drop old partitions | Far cheaper deletes at large scale, but more schema machinery than this volume needs |
| Canonical-JSON fingerprint | Tolerates formatting differences but adds parsing; a genuine retry resends identical bytes |
| Store only 2xx responses | Simpler, but retries of failed requests would re-run instead of replaying the original answer |
| `noRollbackFor = PayFlowException` | Turns translated lock conflicts into 500s via `UnexpectedRollbackException` |
| Scope payments by merchant only | A TEST key could read or cancel LIVE payments |
| Unique `(merchant_id, reference)` | Blocks retrying a declined order with a different card |
| Default-allow gateway authentication | A new route stays unauthenticated until someone remembers to protect it |
| Reject requests that carry `X-PayFlow-*` | Tells a probing client which headers matter; stripping is the standard proxy behaviour |
