#!/usr/bin/env bash
# Hook for PHASE3_E2E_AUTO=1 — drives service/container steps during --with-outages.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PEPPER="${PAYFLOW_API_KEY_PEPPER:-YWJjZGVmZ2hpamsrbG1ub3BxcnN0dXZ3eHl6QUJDREVG}"
PAYMENT_PID=""

start_mock_psp() {
  pkill -f 'mock-psp-0.1.0-SNAPSHOT.jar' 2>/dev/null || true
  sleep 1
  nohup java -jar "$ROOT/services/mock-psp/target/mock-psp-0.1.0-SNAPSHOT.jar" >> /tmp/payflow-mock-psp.log 2>&1 &
  for _ in $(seq 1 20); do curl -sf http://localhost:8083/actuator/health >/dev/null && return 0; sleep 1; done
  echo "mock-psp failed to start" >&2
  exit 1
}

start_payment_service() {
  pkill -f 'payment-service-0.1.0-SNAPSHOT.jar' 2>/dev/null || true
  sleep 1
  nohup env PAYFLOW_PROCESSOR_TYPE=http "$@" \
    java -jar "$ROOT/services/payment-service/target/payment-service-0.1.0-SNAPSHOT.jar" \
    >> /tmp/payflow-payment.log 2>&1 &
  PAYMENT_PID=$!
  for _ in $(seq 1 30); do curl -sf http://localhost:8082/actuator/health >/dev/null && return 0; sleep 1; done
  echo "payment-service failed to start" >&2
  exit 1
}

msg=$1
case "$msg" in
  *"Stop mock-psp"*)
    pkill -f 'mock-psp-0.1.0-SNAPSHOT.jar' 2>/dev/null || true
    sleep 1
    ;;
  *"Start mock-psp"*)
    start_mock_psp
    ;;
  *"Stop payment-service"*)
    pkill -f 'payment-service-0.1.0-SNAPSHOT.jar' 2>/dev/null || true
    sleep 1
    ;;
  *"Start payment-service with PAYFLOW_PROCESSOR_TYPE=http"*)
    start_payment_service
    ;;
  *"wrong_key"*)
    start_payment_service PAYFLOW_PSP_API_KEY=wrong_key
    ;;
  *"correct PAYFLOW_PSP_API_KEY"*)
    start_payment_service
    ;;
  *"docker stop payflow-redis"*)
    docker stop payflow-redis >/dev/null
    sleep 1
    ;;
  *"docker start payflow-redis"*)
    docker start payflow-redis >/dev/null
    ;;
  *)
    ;;
esac
