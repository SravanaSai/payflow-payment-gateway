# ADR-0005: Processor Integration and Resilience

## Status

Accepted

## Context

Phase 3 connects payment-service to an external-style processor (mock-psp) over HTTP, routes all merchant traffic through edge-gateway, and hardens failure modes. Phase 2 already stored idempotency in Postgres with a Redis read-through cache (ADR-0004) and rate-limited at the gateway with Redis (ADR-0003). When Redis is down, both paths fail open but each call still waits for the 200 ms client timeout, adding about 0.6 s per payment replay and about 0.4 s per gateway request.

## Decision

### PaymentProcessor port and strategy

- Authorize and capture go through a `PaymentProcessor` port. `payflow.processor.type` selects `in-memory` (default) or `http`.
- The HTTP adapter is an anti-corruption layer: PayFlow uses camelCase and domain statuses; mock-psp uses snake_case, `approved`/`captured`, and 402 declines with `{"error":{...}}`.
- Outbound idempotency keys are `paymentId:authorize` and `paymentId:capture` (with `:N` suffix after a definite capture decline — see below).

### Transaction split

Authorize and capture use three steps: read payment in a read-only transaction, call the processor with no DB transaction, write result in a separate transaction. A slow PSP must not hold pool connections.

### Capture attempts and outbound keys

- `payments.capture_attempts` counts definite processor declines on capture while the payment stays AUTHORIZED.
- Outbound capture key: attempt 0 → `{paymentId}:capture`; attempt N → `{paymentId}:capture:N`.
- Timeouts and `Unavailable` do not increment attempts (outcome in doubt — retry reuses the same key).
- A definite decline is persisted, then the API returns 422 `PAYMENT_DECLINED` after the write commits.

### Resilience4j on the processor (`psp` instance)

- Decorator order: bulkhead → retry → circuit breaker → HTTP client timeouts.
- Numbers in YAML; retry/breaker exception classification in code (`PspResilience`).
- HTTP read timeout enforces per-attempt budget (no TimeLimiter). Worst-case processor budget ~4.2 s; gateway read timeout 5 s.

### Error mapping (merchant-visible)

| Condition | HTTP | errorCode |
|---|---|---|
| Processor decline (402) | 422 | PAYMENT_DECLINED |
| Processor unavailable / breaker open | 503 | SERVICE_UNAVAILABLE |
| Unexpected processor response | 502 | PROCESSOR_ERROR |
| payment-service unreachable from gateway | 503 | SERVICE_UNAVAILABLE |
| payment-service too slow from gateway | 504 | UPSTREAM_TIMEOUT |

Inbound idempotency: responses below 500 except 409 are stored and replayed with `Idempotency-Replayed: true`. 409/5xx release the merchant key.

### Redis circuit breakers (fail open, skip while open)

Two instances with the same settings: `idempotency-cache` (payment-service) and `rate-limit-redis` (gateway).

- Only the Redis network call runs inside the breaker; JSON parse/serialize stays outside so corrupt cache entries do not trip the breaker.
- While OPEN: skip Redis immediately (metric `skipped` on cache; gateway uses existing `payflow.ratelimit.degraded`).
- `register-health-indicator: false` and `management.health.circuitbreakers.enabled: false` so Redis outages do not mark services unhealthy.
- Window: COUNT_BASED 20, minimum 10 calls, 50% failure, slow-call threshold 100 ms, open 5 s, 3 half-open probes.

### Correlation IDs

payment-service forwards `X-Correlation-Id` to mock-psp; mock-psp logs it on every request.

## Consequences

### Positive

- Merchants can retry capture with a new amount after a decline without hitting PSP idempotency reuse (502).
- Redis outages stop adding ~1 s latency per request once breakers open.
- Full payment path is testable through port 8080 (`scripts/e2e/phase3-e2e.sh`).

### Negative

- In-doubt authorize/capture may leave orphan holds until reconciliation (Phase 6).
- 504 from the gateway means outcome unknown — retry with the same Idempotency-Key.
- mock-psp state is in-memory; restart loses holds.
- Each JVM instance learns breaker state independently (acceptable for latency protection).

### Operational

- Watch `payflow_idempotency_cache_total{result="skipped"}` and `payflow.ratelimit.degraded` during Redis incidents.
- Watch `resilience4j_circuitbreaker_state` for `idempotency-cache` and `rate-limit-redis`.
- See ADR-0004 for idempotency metrics and purge; ADR-0003 for rate-limit behaviour.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| TimeLimiter instead of HTTP read timeout | Double timeout layers; HTTP factory already bounds each attempt |
| Retries inside edge-gateway | Duplicates payment-service retry; harder to reason about total latency |
| Redis fail closed | Would block payments when Redis is optional |
| One outbound capture key per inbound Idempotency-Key | Couples merchant keys to PSP keys; attempt counter is simpler |
| Exception allow-list on Redis breaker | Parsing outside the breaker makes allow-lists unnecessary |
| Fix capture reuse in Phase 5 only | E2E exposed merchant-visible 502 after decline; fixed in Phase 3 |
