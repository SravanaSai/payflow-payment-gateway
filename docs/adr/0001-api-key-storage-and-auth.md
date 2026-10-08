# ADR-0001: API Key Storage and Authentication Model

- **Status:** Accepted
- **Date:** 2026-08-11
- **Phase:** 1

## Context

The edge gateway must authenticate every merchant API request. How we store a
key's secret is constrained by how we verify it:

| Verification method | Server must be able to | Storage that permits it |
| --- | --- | --- |
| Bearer token (client sends the secret) | Compare a submitted value | One-way hash |
| HMAC signature (client sends a signature) | Recompute `HMAC(secret, canonical)` | Reversible (encrypted) |

A one-way hash and HMAC verification are mutually exclusive, so the storage
choice and the authentication scheme are a single decision.

The original Phase 1 plan named Argon2 for hashing and HMAC for inbound request
signing. Those two are incompatible with each other, which forced this analysis.

## Decision

### 1. Bearer token is the primary authentication scheme

The secret is sent as `Authorization: Bearer sk_live_...` and stored as
`HMAC-SHA256(pepper, secret)`.

### 2. Argon2 is not used for API keys

Argon2 is deliberately slow and memory-hard to defend low-entropy human
passwords. Our secrets are 32 bytes from `SecureRandom`:

| | Human password (~40 bits) | API key (256 bits) |
| --- | --- | --- |
| Search space | ~10^12 | ~10^77 |
| At 10^9 fast hashes/sec | ~20 minutes | ~10^61 years |

Brute force is infeasible regardless of hash speed, so Argon2's slowness buys no
security while costing roughly 50-100ms and tens of megabytes per verification
on the gateway's hot path — untenable beyond a few hundred requests per second.

Argon2id remains the correct choice for a future human-login table, where the
input really is low-entropy.

### 3. The hash is keyed with a pepper, not salted

A pepper is a single server-wide secret held outside the database. A leaked
database therefore yields hashes an attacker cannot test candidates against.

Omitting a per-row salt is safe here (no rainbow table can exist for 256-bit
random inputs) and makes hashing deterministic, so the hash column can be
indexed for direct lookup.

### 4. Peppers are versioned

Rotating a pepper invalidates every stored hash, because the original secrets no
longer exist and hashes cannot be recomputed. Storing `pepper_version` alongside
each hash lets old hashes verify against a retained previous pepper and be
re-hashed on their next successful authentication.

### 5. HMAC request signing is a per-merchant opt-in (Phase 1b)

Merchants with `signing_required` additionally sign requests. That signing
secret is stored AES-256-GCM encrypted, since HMAC needs the raw value. This
gains tamper detection and replay protection for merchants who want it, without
imposing canonicalisation complexity on every integrator.

### 6. Pepper and KEK come from environment variables locally

Design assumes externally managed key material (Vault or cloud KMS) in
production; only the source changes, not the code.

## Alternatives considered

**Argon2 hash + bearer, as originally planned.** Rejected on performance: it
would require caching verification results at the gateway, which reintroduces
plaintext secrets in memory plus cache invalidation on revoke — the exact risks
hashing was meant to remove.

**AES-GCM encrypted secret + mandatory HMAC signing (the AWS SigV4 model).**
Rejected as the default. It is stronger on the wire — no reusable credential is
ever transmitted — but it makes the database-leak guarantee conditional on KEK
secrecy, introduces a key management burden, expands PCI audit scope, and shifts
significant integration cost onto every merchant. Signature mismatches from
canonicalisation differences are the dominant support burden for APIs that
require it. Retained as the Phase 1b opt-in rather than discarded.

**Industry precedent** supported this split: Stripe, Razorpay, GitHub and Twilio
all use bearer tokens with hashed storage for inbound API calls and reserve HMAC
for outbound webhooks, where the direction is reversed and hashing was never an
option. AWS is the notable inbound-HMAC exception.

## Consequences

**Positive**

- A database leak alone exposes nothing usable, unconditionally.
- Verification costs microseconds instead of ~50-100ms.
- Merchants integrate with a single header, testable with `curl`.
- Deterministic hashes allow an indexed lookup rather than a scan.
- Pepper rotation is possible without locking merchants out.

**Negative**

- The secret crosses the wire on every request, so TLS plus disciplined header
  redaction in logs is mandatory.
- No tamper detection or replay protection by default; replay is mitigated at
  the business layer by idempotency keys (Phase 2).
- Two authentication code paths to maintain once Phase 1b lands.
- The pepper becomes critical infrastructure: losing it invalidates every key.

## Implementation

`libs/common-security` — `ApiKeyGenerator`, `Pepper`, `PepperRegistry`,
`SecretHasher`, `HashedSecret`. JDK-only, no Spring.
