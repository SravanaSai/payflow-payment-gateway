# PayFlow

PayFlow is a learning payment platform: merchants sign up, receive an API key, and create payments through an edge gateway. Each service owns its database. The domain stays free of HTTP and persistence details.

## Current status

Phases 0–2 are complete and verified locally.

| Phase | What it adds |
|---|---|
| 0 | Maven monorepo, local Docker infrastructure, merchant-service |
| 1 | API keys, edge gateway, authentication, rate limiting |
| 2 | Payment state machine, optimistic locking, idempotency, Redis replay cache |

## Architecture

```text
Merchant app
    |
    v
edge-gateway :8080
    |-- /v1/merchants/** --> merchant-service :8081 --> Postgres database merchant
    |-- /v1/payments/**  --> payment-service  :8082 --> Postgres database payment
    |
    +-- Redis for rate limits and idempotency replay cache
```

A payment request is authenticated at the gateway. The gateway removes the API key and forwards `X-PayFlow-Merchant-Id`, `X-PayFlow-Key-Id`, and `X-PayFlow-Environment`. payment-service never sees the secret.

## Modules

```text
platform-bom                         dependency versions
libs/common-core                     Money, ids, error codes
libs/common-security                 API key generation and hashing
libs/common-web                      correlation ids and RFC 7807 errors
services/merchant-service            merchants and API keys, port 8081
services/payment-service             payment lifecycle, port 8082
services/mock-psp                    fake payment processor for local runs and tests, port 8083
services/edge-gateway                public entry point, port 8080
deploy/docker-compose                Postgres, Redis, Kafka, observability
docs/adr                             architecture decisions
```

## Prerequisites

- Java 21
- Maven 3.9+
- Docker, with Compose

## Run locally

```bash
cp deploy/docker-compose/.env.example deploy/docker-compose/.env
# If port 5432 is taken, set POSTGRES_PORT=5433 in that .env file.

openssl rand -base64 32
# Export the same value for merchant-service and the gateway:
export PAYFLOW_API_KEY_PEPPER='paste-the-value'

docker compose -f deploy/docker-compose/infra.yml up -d postgres redis
mvn -DskipTests install

java -jar services/merchant-service/target/merchant-service-0.1.0-SNAPSHOT.jar
java -jar services/payment-service/target/payment-service-0.1.0-SNAPSHOT.jar
java -jar services/edge-gateway/target/edge-gateway-0.1.0-SNAPSHOT.jar
```

Optional: start the fake payment processor on port 8083. The last four digits of the amount choose the outcome: `0051` declines for insufficient funds, `0052` declines as do-not-honor, `0091` replies slowly, `0092` is down, `0093` fails twice then approves. Any other amount approves. Every call needs `Authorization: Bearer mock_psp_local_key` (override with `MOCKPSP_API_KEY`) and an `Idempotency-Key` header.

```bash
java -jar services/mock-psp/target/mock-psp-0.1.0-SNAPSHOT.jar
```

To have payment-service call mock-psp over HTTP (Resilience4j retry, circuit breaker, bulkhead), start mock-psp first, then:

```bash
PAYFLOW_PROCESSOR_TYPE=http java -jar services/payment-service/target/payment-service-0.1.0-SNAPSHOT.jar
```

Optional overrides: `PAYFLOW_PSP_BASE_URL` (default `http://localhost:8083`), `PAYFLOW_PSP_API_KEY` (default `mock_psp_local_key`).

Sign-up is public. Everything else under `/v1/` needs `Authorization: Bearer <secret>`. Payment commands also need `Idempotency-Key`.

```bash
curl -s -X POST http://localhost:8080/v1/merchants \
  -H 'Content-Type: application/json' \
  -d '{"name":"Acme","email":"ops@acme.test"}'
```

## Phase 3 end-to-end check (through the gateway)

Start infrastructure, build jars, then run **mock-psp**, **merchant-service**, **payment-service** (HTTP processor), and **edge-gateway**. Merchant-service and the gateway need the same `PAYFLOW_API_KEY_PEPPER` (see `application-test.yml` in each module for a local test value).

```bash
export PAYFLOW_API_KEY_PEPPER='YWJjZGVmZ2hpamsrbG1ub3BxcnN0dXZ3eHl6QUJDREVG'
docker compose -f deploy/docker-compose/infra.yml up -d postgres redis
mvn -DskipTests install

java -jar services/mock-psp/target/mock-psp-0.1.0-SNAPSHOT.jar &
java -jar services/merchant-service/target/merchant-service-0.1.0-SNAPSHOT.jar &
PAYFLOW_PROCESSOR_TYPE=http java -jar services/payment-service/target/payment-service-0.1.0-SNAPSHOT.jar &
PAYFLOW_API_KEY_PEPPER="$PAYFLOW_API_KEY_PEPPER" java -jar services/edge-gateway/target/edge-gateway-0.1.0-SNAPSHOT.jar &
```

Run the scripted scenarios (amount magic digits, idempotency, PSP/gateway breakers, capture-after-decline). Optional interactive outage steps (mock-psp down, payment-service down, wrong PSP key, **Redis down**):

```bash
./scripts/e2e/phase3-e2e.sh
./scripts/e2e/phase3-e2e.sh --with-outages
```

Design notes for Phase 3 live in [docs/adr/0005-processor-integration-and-resilience.md](docs/adr/0005-processor-integration-and-resilience.md).

Gateway timeout (504 `UPSTREAM_TIMEOUT`) is covered by JUnit (`EdgeGatewayUpstreamFailureTest`), not the live script — payment-service gives up before the gateway’s 5s read timeout.

## Tests

```bash
mvn clean install
```

payment-service and edge-gateway integration tests start Postgres and Redis with Testcontainers, so Docker must be running.

## Local URLs

| Service | URL |
|---|---|
| Edge gateway | http://localhost:8080 |
| Merchant API docs | http://localhost:8081/swagger-ui.html |
| Payment API docs | http://localhost:8082/swagger-ui.html |
| Mock PSP (fake processor) | http://localhost:8083 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
