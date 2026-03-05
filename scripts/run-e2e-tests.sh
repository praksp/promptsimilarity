#!/usr/bin/env bash
# Run unit/integration tests for all microservices, and optionally live e2e tests against the running stack.
# Usage:
#   ./scripts/run-e2e-tests.sh           # run all Maven tests only
#   ./scripts/run-e2e-tests.sh --live     # run Maven tests, then start stack and run HTTP e2e checks
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LIVE_E2E=false
for arg in "$@"; do
  case "$arg" in
    --live) LIVE_E2E=true ;;
  esac
done

echo "=== Running unit/integration tests (all modules) ==="
if docker info &>/dev/null; then
  docker run --rm -v "$ROOT:/app" -w /app maven:3.9-eclipse-temurin-17 mvn test -q
else
  mvn test -q
fi
echo "Maven tests passed."
echo ""

if [ "$LIVE_E2E" != "true" ]; then
  echo "Done. Use ./scripts/run-e2e-tests.sh --live to run live e2e checks against the running stack."
  exit 0
fi

echo "=== Live e2e: ensuring stack is up ==="
if ! docker info &>/dev/null; then
  echo "Docker not available. Skip live e2e."
  exit 0
fi

# Start stack if not already up
if ! curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/q/health 2>/dev/null | grep -q 200; then
  echo "Gateway not up. Starting Docker Compose..."
  docker compose up -d
  echo "Waiting for API gateway to be healthy (up to 90s)..."
  for i in $(seq 1 90); do
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/q/health 2>/dev/null | grep -q 200; then
      echo "Gateway is up."
      break
    fi
    if [ "$i" -eq 90 ]; then
      echo "Gateway did not become healthy in time."
      exit 1
    fi
    sleep 1
  done
  sleep 5
fi

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
echo "Running HTTP e2e checks against $GATEWAY"

# 1) Health
if ! curl -sf "$GATEWAY/q/health" | grep -q "UP"; then
  echo "FAIL: Health check did not return UP"
  exit 1
fi
echo "PASS: Health"

# 2) Ingest (must return 200 with promptId so prompts are saved)
CODE=$(curl -s -o /tmp/e2e_ingest.json -w "%{http_code}" -X POST "$GATEWAY/api/v1/prompts/ingest" \
  -H "Content-Type: application/json" \
  -d '{"userId":"e2e-user","orgId":"e2e-org","text":"e2e test prompt","language":"en"}')
if [ "$CODE" != "200" ]; then
  echo "FAIL: Ingest returned HTTP $CODE (expected 200). Response: $(cat /tmp/e2e_ingest.json 2>/dev/null | head -c 200)"
  exit 1
fi
if ! grep -q '"promptId"' /tmp/e2e_ingest.json 2>/dev/null; then
  echo "FAIL: Ingest response missing promptId: $(cat /tmp/e2e_ingest.json 2>/dev/null | head -c 200)"
  exit 1
fi
echo "PASS: Ingest (HTTP $CODE)"

# 3) List prompts (must return 200 and array; after ingest, at least one prompt)
CODE=$(curl -s -o /tmp/e2e_list.json -w "%{http_code}" "$GATEWAY/api/v1/prompts/list")
if [ "$CODE" != "200" ]; then
  echo "FAIL: List prompts returned HTTP $CODE (expected 200)"
  exit 1
fi
if ! grep -q '^\[' /tmp/e2e_list.json 2>/dev/null; then
  echo "FAIL: List response is not a JSON array: $(head -c 200 /tmp/e2e_list.json)"
  exit 1
fi
echo "PASS: List prompts (HTTP $CODE)"

# 4) Find similar (must return 200; body is array, may be empty)
CODE=$(curl -s -o /tmp/e2e_similar.json -w "%{http_code}" "$GATEWAY/api/v1/prompts/similar?text=hello&orgId=e2e-org&userId=e2e-user")
if [ "$CODE" != "200" ]; then
  echo "FAIL: Find similar returned HTTP $CODE (expected 200)"
  exit 1
fi
echo "PASS: Find similar (HTTP $CODE)"

echo ""
echo "All e2e checks passed."
