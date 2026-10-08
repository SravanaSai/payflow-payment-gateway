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

Sign-up is public. Everything else under `/v1/` needs `Authorization: Bearer <secret>`. Payment commands also need `Idempotency-Key`.

```bash
curl -s -X POST http://localhost:8080/v1/merchants \
  -H 'Content-Type: application/json' \
  -d '{"name":"Acme","email":"ops@acme.test"}'
```

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
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
