#!/usr/bin/env bash
# Build and run the Prompt Similarity app end-to-end.
# Without --force: incremental build (only rebuild what's needed, Docker cache used).
# With --force: full clean, rebuild all services, no Docker cache.
# With --test: after startup, run a smoke test (ingest, similar-responses, ask-llm).
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FORCE=false
RUN_TEST=false
for arg in "$@"; do
  case "$arg" in
    --force|-f) FORCE=true ;;
    --test|-t)  RUN_TEST=true ;;
    --help|-h)
      echo "Usage: $0 [--force] [--test] [--help]"
      echo "  --force, -f   Clean all build artifacts and rebuild everything (no cache)."
      echo "  --test, -t   After startup, run smoke test (ingest, RAG, ask-llm)."
      echo "  (default)    Incremental build, use Docker cache."
      exit 0
      ;;
  esac
done

# Ensure Docker is running
if ! docker info &>/dev/null; then
  source "$ROOT/scripts/ensure-docker.sh"
  ensure_docker || { echo "Could not start Docker. Exiting."; exit 1; }
  echo ""
fi

# ---------- Clean (only when --force) ----------
if [ "$FORCE" = true ]; then
  echo "=== Clean (--force) ==="
  rm -rf proto/target
  rm -rf api-gateway/target
  rm -rf prompt-service/target
  rm -rf vector-service/target
  rm -rf search-service/target
  rm -rf feature-store-service/target
  rm -rf notification-service/target
  rm -rf collaboration-service/target
  rm -rf graph-service/target
  rm -rf dashboard/dist
  echo "Clean done."
  echo ""
fi

# ---------- Build Java ----------
echo "=== Build Java ==="
MVN_OPTS="-DskipTests -Dquarkus.package.type=fast-jar -q"
if [ "$FORCE" = true ]; then
  MVN_GOAL="clean package"
else
  MVN_GOAL="package"
fi

if docker info &>/dev/null; then
  echo "Building Java services with Maven in Docker ($MVN_GOAL)..."
  docker run --rm -v "$ROOT:/app" -w /app maven:3.9-eclipse-temurin-17 mvn $MVN_GOAL $MVN_OPTS
  echo "Java build done."
else
  if command -v mvn &>/dev/null && java -version 2>&1 | grep -q 'version "17'; then
    echo "Building Java services with local Maven ($MVN_GOAL)..."
    mvn $MVN_GOAL $MVN_OPTS
    echo "Java build done."
  else
    echo "Skip Java build: Docker not running and Maven/Java 17 not found."
    echo "  Start Docker Desktop and re-run this script for full e2e."
  fi
fi
echo ""

# ---------- Build Dashboard ----------
echo "=== Build Dashboard ==="
cd "$ROOT/dashboard"
if [ "$FORCE" = true ]; then
  npm ci --silent 2>/dev/null || npm install --silent
fi
npm run build
cd "$ROOT"
echo "Dashboard build done."
echo ""

# ---------- Embedding service (Python) ----------
if [ -d "$ROOT/embedding-service" ]; then
  if [ ! -d "$ROOT/embedding-service/venv" ]; then
    echo "Creating embedding-service venv..."
    python3 -m venv "$ROOT/embedding-service/venv"
    "$ROOT/embedding-service/venv/bin/pip" install -r "$ROOT/embedding-service/requirements.txt" -q
  fi
fi

# ---------- Build Docker images ----------
echo "=== Build Docker images ==="
if docker info &>/dev/null; then
  if [ "$FORCE" = true ]; then
    docker compose build --no-cache
  else
    docker compose build
  fi
  echo ""
fi

# ---------- Run ----------
echo "=== Run ==="
if docker info &>/dev/null; then
  echo "Starting full stack with Docker Compose..."
  docker compose up -d
  echo ""
  echo "Services:"
  echo "  Gateway:    http://localhost:8080"
  echo "  Dashboard:  http://localhost:3000 (starting in background)"
  echo "  Embedding:  http://localhost:8081"
  echo "  Ollama:     http://localhost:11434 (RAG/LLM)"
  echo ""
  echo "  docker compose logs -f              # follow logs"
  echo "  docker compose logs -f ollama        # Ollama logs"
  echo ""

  # Wait for Ollama to be reachable (required for LLM; prompt-service uses http://ollama:11434)
  if command -v curl &>/dev/null; then
    echo -n "Waiting for Ollama (RAG/LLM at :11434)... "
    n=0
    while [ $n -lt 45 ]; do
      if curl -s -o /dev/null -w "%{http_code}" --connect-timeout 2 http://localhost:11434/api/version 2>/dev/null | grep -q 200; then
        echo "up."
        break
      fi
      n=$((n + 1))
      if [ $n -eq 1 ]; then echo ""; fi
      echo "  waiting for Ollama ($n/45)..."
      sleep 2
    done
    if [ $n -lt 45 ]; then
      # One-time: pull llama2 only if not already present (persists in volume)
      if ! docker compose exec -T ollama ollama list 2>/dev/null | grep -qE '\bllama2\b'; then
        echo "Pulling llama2 (one-time; persists in volume)..."
        docker compose exec ollama ollama pull llama2
        echo "llama2 ready."
      else
        echo "llama2 already present (skipping pull)."
      fi
    else
      echo "  Ollama not responding after 90s. Start Ollama or check: docker compose logs ollama"
    fi
  fi

  # Start dashboard in background (dev server at :3000)
  if ! curl -s -o /dev/null -w "%{http_code}" --connect-timeout 1 http://localhost:3000 2>/dev/null | grep -q 200; then
    echo "Starting dashboard at http://localhost:3000..."
    (cd "$ROOT/dashboard" && npm run dev) &
    sleep 3
    echo "Dashboard started (or starting). Open http://localhost:3000"
  else
    echo "Dashboard already running at http://localhost:3000"
  fi
  echo ""

  # Smoke test (--test)
  if [ "$RUN_TEST" = true ] && command -v curl &>/dev/null; then
    echo ""
    echo "=== Smoke test (--test) ==="
    GATEWAY="http://localhost:8080"
    FAIL=0
    wait_for_url() {
      local url=$1
      local name=$2
      local max=${3:-30}
      local i=0
      while [ $i -lt "$max" ]; do
        if curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null | grep -q 200; then
          echo "  OK $name"
          return 0
        fi
        i=$((i + 1))
        sleep 2
      done
      echo "  FAIL $name (timeout)"
      return 1
    }
    if ! wait_for_url "$GATEWAY/q/health" "Gateway health" 20; then FAIL=1; fi
    if [ $FAIL -eq 0 ]; then
      INGEST=$(curl -s -X POST "$GATEWAY/api/v1/prompts/ingest" \
        -H "Content-Type: application/json" \
        -d '{"userId":"smoke-user","orgId":"default-org","text":"smoke test prompt","language":"en"}' \
        -w "\n%{http_code}" 2>/dev/null)
      if echo "$INGEST" | tail -1 | grep -q 200; then
        echo "  OK POST /api/v1/prompts/ingest"
      else
        echo "  FAIL POST /api/v1/prompts/ingest (expected 200)"
        FAIL=1
      fi
      RAG=$(curl -s -X POST "$GATEWAY/api/v1/prompts/rag/similar-responses" \
        -H "Content-Type: application/json" \
        -d '{"prompt":"smoke test","userId":"smoke-user","orgId":"default-org"}' \
        -w "\n%{http_code}" 2>/dev/null)
      if echo "$RAG" | tail -1 | grep -q 200; then
        echo "  OK POST /api/v1/prompts/rag/similar-responses"
      else
        echo "  FAIL POST /api/v1/prompts/rag/similar-responses"
        FAIL=1
      fi
      ASK_LLM=$(curl -s -X POST "$GATEWAY/api/v1/prompts/rag/ask-llm" \
        -H "Content-Type: application/json" \
        -d '{"prompt":"Hi","userId":"smoke-user","orgId":"default-org"}' \
        -w "\n%{http_code}" --max-time 120 2>/dev/null)
      if echo "$ASK_LLM" | tail -1 | grep -q 200; then
        echo "  OK POST /api/v1/prompts/rag/ask-llm"
      else
        echo "  FAIL POST /api/v1/prompts/rag/ask-llm (timeout or non-200)"
        FAIL=1
      fi
    fi
    if [ $FAIL -eq 0 ]; then
      echo "  Smoke test passed."
    else
      echo "  Smoke test had failures."
      exit 1
    fi
  fi
else
  echo "Docker not running. Starting embedding service and dashboard only."
  echo "  For full e2e: start Docker Desktop and run this script again."
  echo ""
  if ! curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/health 2>/dev/null | grep -q 200; then
    echo "Starting embedding service on :8081..."
    (cd "$ROOT/embedding-service" && ./venv/bin/uvicorn main:app --host 0.0.0.0 --port 8081) &
    sleep 3
  else
    echo "Embedding service already running on :8081"
  fi
  echo "Ollama (RAG): ensure Ollama is running (e.g. ollama serve) at http://localhost:11434"
  echo "Starting dashboard on :3000..."
  cd "$ROOT/dashboard" && npm run dev
fi
