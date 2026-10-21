#!/usr/bin/env bash
# End-to-end Phase 3 check through edge-gateway :8080.
# Prerequisites: curl, jq, uuidgen; Postgres + Redis; mock-psp, merchant-service,
# payment-service (PAYFLOW_PROCESSOR_TYPE=http), edge-gateway (PAYFLOW_API_KEY_PEPPER set).
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
WITH_OUTAGES=false
FAILURES=0
LAST_STATUS=""
LAST_BODY=""
LAST_HEADERS=""
LAST_TIME=""

usage() {
  echo "Usage: $0 [--with-outages]"
  echo "  --with-outages  interactive stops for mock-psp / payment-service outage scenarios"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-outages) WITH_OUTAGES=true; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

pass() { echo "PASS: $*" >&2; }
fail() { echo "FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }

expect_status() {
  local want=$1
  if [[ "$LAST_STATUS" == "$want" ]]; then
    pass "HTTP $want"
  else
    fail "expected HTTP $want, got $LAST_STATUS — body: $LAST_BODY"
  fi
}

expect_json() {
  local filter=$1 want=$2
  local got
  got=$(echo "$LAST_BODY" | jq -r "$filter" 2>/dev/null || echo "")
  if [[ "$got" == "$want" ]]; then
    pass "$filter = $want"
  else
    fail "expected $filter = $want, got '$got' — body: $LAST_BODY"
  fi
}

expect_header() {
  local name=$1 want=$2
  local got
  got=$(echo "$LAST_HEADERS" | grep -i "^${name}:" | head -1 | sed 's/^[^:]*:[[:space:]]*//' | tr -d '\r')
  if [[ "${got:-}" == "$want" ]]; then
    pass "header $name = $want"
  else
    fail "expected header $name = $want, got '${got:-<missing>}'"
  fi
}

expect_header_absent() {
  local name=$1
  if echo "$LAST_HEADERS" | grep -qi "^${name}:"; then
    fail "expected header $name to be absent"
  else
    pass "header $name absent"
  fi
}

pause_manual() {
  if [[ "${PHASE3_E2E_AUTO:-}" == "1" ]]; then
    echo "[auto] $1" >&2
    if [[ -x "${PHASE3_E2E_AUTO_HOOK:-}" ]]; then
      "$PHASE3_E2E_AUTO_HOOK" "$1" || exit $?
    fi
    return 0
  fi
  echo "" >&2
  echo "$1" >&2
  read -r -p "Press Enter when ready..." >&2
  echo "" >&2
}

api() {
  local method=$1 path=$2 key=$3 idem=${4:-} body=${5:-} corr=${6:-}
  local tmp
  tmp=$(mktemp)
  local args=(-sS -w "\n%{http_code} %{time_total}" -X "$method" "${GATEWAY_URL}${path}")
  args+=(-H "Content-Type: application/json")
  if [[ -n "$key" ]]; then
    args+=(-H "Authorization: Bearer ${key}")
  fi
  if [[ -n "$idem" ]]; then
    args+=(-H "Idempotency-Key: ${idem}")
  fi
  if [[ -n "$corr" ]]; then
    args+=(-H "X-Correlation-Id: ${corr}")
  fi
  if [[ -n "$body" ]]; then
    args+=(-d "$body")
  fi
  args+=(-D "$tmp")
  local raw
  raw=$(curl "${args[@]}")
  local trailer
  trailer=$(echo "$raw" | tail -n1)
  LAST_STATUS=$(echo "$trailer" | awk '{print $1}')
  LAST_TIME=$(echo "$trailer" | awk '{print $2}')
  LAST_BODY=$(echo "$raw" | sed '$d')
  LAST_HEADERS=$(cat "$tmp")
  rm -f "$tmp"
  sleep 0.2
}

create_merchant() {
  local suffix=$1
  api POST "/v1/merchants" "" "" "{\"name\":\"E2E ${suffix}\",\"email\":\"e2e-${suffix}@example.test\"}"
  expect_status 201
  echo "$LAST_BODY" | jq -r '.apiKey.secret'
}

create_payment() {
  local key=$1 amount=$2
  local idem
  idem=$(uuidgen)
  api POST "/v1/payments" "$key" "$idem" "{\"amountMinor\":${amount},\"currency\":\"INR\"}"
  expect_status 201
  echo "$LAST_BODY" | jq -r '.id'
}

authorize() {
  local key=$1 pid=$2
  local idem
  idem=$(uuidgen)
  api POST "/v1/payments/${pid}/authorize" "$key" "$idem" ""
  LAST_STATUS="$LAST_STATUS"
}

authorize_with_key() {
  local key=$1 pid=$2 idem=$3
  api POST "/v1/payments/${pid}/authorize" "$key" "$idem" ""
}

capture() {
  local key=$1 pid=$2 amount=$3
  local idem
  idem=$(uuidgen)
  api POST "/v1/payments/${pid}/capture" "$key" "$idem" "{\"amountMinor\":${amount}}"
}

get_payment() {
  local key=$1 pid=$2
  api GET "/v1/payments/${pid}" "$key" "" ""
}

echo "=== Phase 3 E2E via ${GATEWAY_URL} ===" >&2

KEY_A=$(create_merchant "a-$(uuidgen | tr '[:upper:]' '[:lower:]')")
KEY_B=$(create_merchant "b-$(uuidgen | tr '[:upper:]' '[:lower:]')")
echo "Merchant A key prefix: ${KEY_A:0:12}..." >&2

echo "" >&2
echo "--- 1. Happy path: create, authorize, capture, GET ---" >&2
PID=$(create_payment "$KEY_A" 150000)
authorize "$KEY_A" "$PID"
expect_status 200
expect_json '.status' 'AUTHORIZED'
capture "$KEY_A" "$PID" 100000
expect_status 200
expect_json '.status' 'CAPTURED'
expect_json '.capturedAmountMinor' '100000'
get_payment "$KEY_A" "$PID"
expect_json '.status' 'CAPTURED'

echo "" >&2
echo "--- 2. Declined authorize + replay ---" >&2
PID=$(create_payment "$KEY_A" 150051)
IDEM=$(uuidgen)
authorize_with_key "$KEY_A" "$PID" "$IDEM"
expect_status 422
expect_json '.errorCode' 'PAYMENT_DECLINED'
get_payment "$KEY_A" "$PID"
expect_json '.status' 'FAILED'
authorize_with_key "$KEY_A" "$PID" "$IDEM"
expect_status 422
expect_header 'Idempotency-Replayed' 'true'

echo "" >&2
echo "--- 3. Slow authorize (0091) ---" >&2
PID=$(create_payment "$KEY_A" 150091)
authorize "$KEY_A" "$PID"
expect_status 200
expect_json '.status' 'AUTHORIZED'

echo "" >&2
echo "--- 4. Flaky authorize (0093) ---" >&2
PID=$(create_payment "$KEY_A" 150093)
authorize "$KEY_A" "$PID"
expect_status 200
expect_json '.status' 'AUTHORIZED'

echo "" >&2
echo "--- 5. Processor down (0092) + retry releases key ---" >&2
PID=$(create_payment "$KEY_A" 150092)
IDEM=$(uuidgen)
authorize_with_key "$KEY_A" "$PID" "$IDEM"
expect_status 503
expect_json '.errorCode' 'SERVICE_UNAVAILABLE'
get_payment "$KEY_A" "$PID"
expect_json '.status' 'PENDING'
authorize_with_key "$KEY_A" "$PID" "$IDEM"
expect_status 503
expect_header_absent 'Idempotency-Replayed'

echo "Waiting 12s for circuit breaker to close after down scenarios..." >&2
sleep 12

echo "" >&2
echo "--- 6. Declined capture leaves AUTHORIZED ---" >&2
PID=$(create_payment "$KEY_A" 150000)
authorize "$KEY_A" "$PID"
expect_status 200
capture "$KEY_A" "$PID" 100051
expect_status 422
get_payment "$KEY_A" "$PID"
expect_json '.status' 'AUTHORIZED'
capture "$KEY_A" "$PID" 100000
expect_status 200
expect_json '.status' 'CAPTURED'

echo "" >&2
echo "--- 7. Capture outage (100092) ---" >&2
PID=$(create_payment "$KEY_A" 150000)
authorize "$KEY_A" "$PID"
expect_status 200
capture "$KEY_A" "$PID" 100092
expect_status 503
get_payment "$KEY_A" "$PID"
expect_json '.status' 'AUTHORIZED'

echo "" >&2
echo "--- 8. Circuit breaker opens then recovers ---" >&2
for _ in 1 2; do
  p=$(create_payment "$KEY_A" 150092)
  authorize "$KEY_A" "$p"
  expect_status 503
done
p=$(create_payment "$KEY_A" 150000)
authorize "$KEY_A" "$p"
expect_status 503
if awk "BEGIN {exit !($LAST_TIME < 0.15)}"; then
  pass "fast reject while breaker open (${LAST_TIME}s)"
else
  fail "expected fast reject while breaker open, took ${LAST_TIME}s"
fi
echo "Waiting 11s for breaker half-open..." >&2
sleep 11
p=$(create_payment "$KEY_A" 150000)
authorize "$KEY_A" "$p"
expect_status 200
p=$(create_payment "$KEY_A" 150000)
authorize "$KEY_A" "$p"
expect_status 200

echo "" >&2
echo "--- 9. Correlation ID ---" >&2
CORR="e2e-$(uuidgen)"
PID=$(create_payment "$KEY_A" 150091)
idem=$(uuidgen)
api POST "/v1/payments/${PID}/authorize" "$KEY_A" "$idem" "" "$CORR"
expect_status 200
if echo "$LAST_HEADERS" | grep -qi "^x-correlation-id:.*${CORR}"; then
  pass "response echoes X-Correlation-Id"
else
  fail "response missing X-Correlation-Id echo"
fi
echo "Grep logs: correlationId=${CORR}  (payment-service + mock-psp JSON logs)" >&2

echo "" >&2
echo "--- 10. Merchant isolation ---" >&2
PID=$(create_payment "$KEY_A" 150000)
get_payment "$KEY_B" "$PID"
expect_status 404
expect_json '.errorCode' 'PAYMENT_NOT_FOUND'

if [[ "$WITH_OUTAGES" == true ]]; then
  echo ""
  echo "--- Outage: mock-psp stopped ---"
  pause_manual "Stop mock-psp, then press Enter."
  PID=$(create_payment "$KEY_A" 150000)
  authorize "$KEY_A" "$PID"
  expect_status 503
  get_payment "$KEY_A" "$PID"
  expect_json '.status' 'PENDING'
  pause_manual "Start mock-psp again, then press Enter."

  echo "" >&2
  echo "--- Outage: payment-service stopped ---" >&2
  pause_manual "Stop payment-service, then press Enter."
  idem=$(uuidgen)
  api POST "/v1/payments" "$KEY_A" "$idem" "{\"amountMinor\":150000,\"currency\":\"INR\"}"
  expect_status 503
  expect_json '.errorCode' 'SERVICE_UNAVAILABLE'
  pause_manual "Start payment-service with PAYFLOW_PROCESSOR_TYPE=http, then press Enter."

  echo ""
  echo "--- Outage: wrong PSP API key ---"
  pause_manual "Restart payment-service with PAYFLOW_PSP_API_KEY=wrong_key (mock-psp still on default key), then press Enter."
  PID=$(create_payment "$KEY_A" 150000)
  authorize "$KEY_A" "$PID"
  expect_status 502
  expect_json '.errorCode' 'PROCESSOR_ERROR'
  pause_manual "Restart payment-service with the correct PAYFLOW_PSP_API_KEY, then press Enter."
  echo "Waiting 10s for PSP circuit breaker to settle..." >&2
  sleep 10

  echo "" >&2
  echo "--- Outage: Redis stopped ---" >&2
  pause_manual "Run: docker stop payflow-redis — then press Enter."
  IDEM=$(uuidgen)
  PID=$(create_payment "$KEY_A" 150000)
  authorize_with_key "$KEY_A" "$PID" "$IDEM"
  expect_status 200
  for i in $(seq 1 10); do
    authorize_with_key "$KEY_A" "$PID" "$IDEM"
    expect_status 200
    expect_header 'Idempotency-Replayed' 'true'
    if [[ "$i" -ge 8 ]]; then
      if awk "BEGIN {exit !($LAST_TIME < 0.15)}"; then
        pass "replay ${i} fast with Redis down (${LAST_TIME}s)"
      else
        fail "replay ${i} slow with Redis down (${LAST_TIME}s)"
      fi
    fi
  done
  if curl -sf "http://localhost:8082/actuator/prometheus" | grep -qE 'idempotency-cache.*state="open".* 1(\.0)?$'; then
    pass "payment-service idempotency-cache breaker open"
  else
    fail "payment-service idempotency-cache breaker not open in metrics"
  fi
  if curl -sf "http://localhost:8080/actuator/prometheus" | grep -qE 'rate-limit-redis.*state="open".* 1(\.0)?$'; then
    pass "gateway rate-limit-redis breaker open"
  else
    fail "gateway rate-limit-redis breaker not open in metrics"
  fi
  pause_manual "Run: docker start payflow-redis — wait 6s — then press Enter."
  sleep 6
  for i in $(seq 1 4); do
    authorize_with_key "$KEY_A" "$PID" "$IDEM"
    expect_status 200
    expect_header 'Idempotency-Replayed' 'true'
  done
  if curl -sf "http://localhost:8082/actuator/prometheus" | grep -qE 'idempotency-cache.*state="closed".* 1(\.0)?$'; then
    pass "payment-service idempotency-cache breaker closed"
  else
    fail "payment-service idempotency-cache breaker not closed after Redis up"
  fi
fi

echo ""
if [[ "$FAILURES" -eq 0 ]]; then
  echo "=== All E2E checks passed ==="
  exit 0
fi
echo "=== $FAILURES check(s) failed ==="
exit 1
