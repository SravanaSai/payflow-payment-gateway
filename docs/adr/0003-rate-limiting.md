# ADR-0003: Edge Gateway Rate Limiting

## Status

Accepted

## Context

Phase 1a Step 4 added API-key authentication at the edge gateway. A valid or stolen key can still flood the platform, and invalid bearer tokens force an expensive hash lookup in merchant-service on every request. Public onboarding (`POST /v1/merchants`) has no merchant identity yet.

Redis 7 is already provisioned in Docker Compose for rate limiting, idempotency (Phase 2), and velocity checks (Phase 8).

## Decision

Implement **two token-bucket rate limiters** at the edge gateway using a **Redis Lua script** for atomic read-modify-write:

1. **IP limiter** (filter order `+10`, before auth) — key `rl:ip:{clientIp}`. Protects auth lookups and public signup from floods.
2. **Merchant limiter** (filter order `+30`, after auth) — key `rl:mkt:{merchantId}:{environment}`. Enforces per-merchant business quotas with TEST and LIVE isolated.

### Algorithm

Token bucket with lazy refill stored as `{tokens, ts}` in a Redis hash. TTL = `ceil(capacity / refillRate) + 1` seconds so idle buckets expire without a background sweeper.

### Client IP

Default to `HttpServletRequest.getRemoteAddr()`. Read `X-Forwarded-For` only when `payflow.gateway.rate-limit.trust-forwarded-for=true` (production behind a trusted load balancer). IPv6 addresses are masked to `/64` before keying.

### Failure mode

**Fail open** on Redis errors: allow the request, log WARN, increment `payflow.ratelimit.degraded` metric. Rate limiting is protective, not correctness-critical; a Redis outage must not stop payment traffic.

### Response contract

- Allowed and denied responses include `X-RateLimit-Limit` and `X-RateLimit-Remaining`.
- Denied responses return HTTP 429 with `Retry-After`, `X-RateLimit-Reset`, and `errorCode: RATE_LIMITED` (RFC 7807).

## Consequences

### Positive

- Closes the auth lookup amplification hole (invalid tokens capped at IP tier before merchant-service is contacted).
- TEST integration traffic cannot exhaust LIVE production quotas.
- Key rotation does not reset merchant buckets (keyed by merchant, not keyId).
- Lua script gives atomicity under concurrent load with a single round trip.

### Negative

- Two Redis round trips per authenticated request (+200–400 ms worst case if Redis is slow until the rate-limit-redis circuit breaker opens; see ADR-0005).
- NAT/shared IP addresses share the anonymous bucket (mitigated by generous anonymous tier).
- Distributed botnets with many IPs are not fully stopped by IP limiting alone (merchant tier + future WAF).

### Operational

- Alert on `payflow.ratelimit.degraded` — sustained elevation means Redis is down and the gateway is running without limits.
- Default tiers (local): anonymous 100/20s, LIVE 100/50s, TEST 20/10s — tune per environment.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Fixed window | Boundary burst allows 2× traffic at minute rollovers |
| Sliding window log | Memory grows with request volume |
| Bucket4j library | Plan called for custom Lua for learning; equivalent capability |
| Single merchant-only limiter | Does not protect pre-auth paths (invalid tokens, signup spam) |
| Fail closed on Redis errors | Redis outage would stop all API traffic |
| Key by keyId | Rotation grants a fresh unlimited bucket |
