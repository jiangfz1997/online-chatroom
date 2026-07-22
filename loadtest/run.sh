#!/usr/bin/env bash
# Load-test run orchestrator (plan §2.5.C): health-wait -> run k6 scenario ->
# export results -> reset state, so every run starts from the same clean
# baseline and results across tuning iterations are actually comparable.
#
# Usage:
#   ./run.sh <scenario> <RUN_TAG> [-- <k6 -e KEY=VAL ...>]
# Examples:
#   ./run.sh baseline baseline-kafka-01
#   ./run.sh baseline tune-03-sqs-batch -- -e VUS=50 -e MSG_INTERVAL_MS=500
#
# Assumes docker-compose.loadtest.yml (app) and docker-compose.obs.yml
# (Prometheus/Grafana/Loki) are already up.
set -euo pipefail

SCENARIO="${1:?usage: run.sh <scenario> <RUN_TAG> [-- k6-args...]}"
RUN_TAG="${2:?usage: run.sh <scenario> <RUN_TAG> [-- k6-args...]}"
shift 2
if [ "${1:-}" = "--" ]; then shift; fi

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RESULTS_DIR="results/${RUN_TAG}"
mkdir -p "$RESULTS_DIR"

# Health checks run on the host, hitting the same published ports the app
# containers expose (docker-compose.loadtest.yml). k6 itself runs inside its
# own container, so it reaches the app over host.docker.internal instead
# (see API_URL/WS*_URL below) — same pattern as Prometheus/Promtail already use.
API_URL_HOST="http://localhost:8080/api/health"
WS1_HEALTH="http://localhost:8081/actuator/health"
WS2_HEALTH="http://localhost:8082/actuator/health"
PERSIST_HEALTH="http://localhost:8090/actuator/health"

API_URL="${API_URL:-http://host.docker.internal:8080}"
WS1_URL="${WS1_URL:-ws://host.docker.internal:8081}"
WS2_URL="${WS2_URL:-ws://host.docker.internal:8082}"
PROM_RW_URL="${PROM_RW_URL:-http://host.docker.internal:9090/api/v1/write}"
PROM_RW_TREND_STATS="${PROM_RW_TREND_STATS:-p(95),p(99),avg,min,max}"

wait_healthy() {
  local name="$1" url="$2" tries=0 max=90
  echo "[$RUN_TAG] waiting for $name ($url) ..."
  until curl -sf "$url" > /dev/null 2>&1; do
    tries=$((tries + 1))
    if [ "$tries" -ge "$max" ]; then
      echo "ERROR: $name did not become healthy after $((max * 2))s" >&2
      exit 1
    fi
    sleep 2
  done
  echo "[$RUN_TAG] $name is up."
}

echo "=== [$RUN_TAG] 1/4 health checks ==="
wait_healthy api-server "$API_URL_HOST"
wait_healthy ws1 "$WS1_HEALTH"
wait_healthy ws2 "$WS2_HEALTH"
wait_healthy persist-worker "$PERSIST_HEALTH"

echo "=== [$RUN_TAG] 2/4 running k6 scenario: $SCENARIO ==="
set +e
# MSYS_NO_PATHCONV: git-bash on Windows rewrites leading-/ arguments (like -w
# /work/k6) into bogus Windows paths before they ever reach docker; this opts
# the whole command out of that rewriting.
MSYS_NO_PATHCONV=1 docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -v "$PWD:/work" -w /work/k6 \
  -e API_URL="$API_URL" -e WS1_URL="$WS1_URL" -e WS2_URL="$WS2_URL" \
  -e K6_PROMETHEUS_RW_SERVER_URL="$PROM_RW_URL" \
  -e K6_PROMETHEUS_RW_TREND_STATS="$PROM_RW_TREND_STATS" \
  grafana/k6 run \
    --out experimental-prometheus-rw \
    --tag testid="$RUN_TAG" \
    --summary-export="/work/${RESULTS_DIR}/summary.json" \
    "scenarios/${SCENARIO}.js" \
    "$@"
K6_EXIT=$?
set -e
echo "=== [$RUN_TAG] 3/4 k6 summary -> ${RESULTS_DIR}/summary.json (k6 exit=$K6_EXIT) ==="

echo "=== [$RUN_TAG] 4/4 resetting state for next run ==="
docker exec loadtest-app-redis redis-cli FLUSHALL > /dev/null
# dynamodb-local runs -inMemory, so a restart wipes all tables; api-server
# recreates them empty via its @PostConstruct DynamoDbInitializer. ws1/ws2/
# persist-worker don't create tables themselves so they don't need a restart.
docker compose -f docker-compose.loadtest.yml restart dynamodb-local > /dev/null
docker compose -f docker-compose.loadtest.yml restart api-server > /dev/null
echo "[$RUN_TAG] redis flushed, DynamoDB Local tables recreated empty."

echo "=== [$RUN_TAG] done (k6 exit=$K6_EXIT) ==="
exit "$K6_EXIT"
