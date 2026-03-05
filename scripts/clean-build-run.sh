#!/usr/bin/env bash
# Build and run the Prompt Similarity app end-to-end.
# Without --force: incremental build (only rebuild what's needed, Docker cache used).
# With --force: full clean, rebuild all services, no Docker cache.
# Ensures Docker and Ollama-related services are up.
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FORCE=false
for arg in "$@"; do
  case "$arg" in
    --force|-f) FORCE=true ;;
    --help|-h)
      echo "Usage: $0 [--force|--help]"
      echo "  --force, -f   Clean all build artifacts and rebuild everything (no cache)."
      echo "  (default)     Incremental build: only rebuild changed modules, use Docker cache."
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
  echo "  Dashboard:  http://localhost:3000 (run: cd dashboard && npm run dev)"
  echo "  Embedding:  http://localhost:8081"
  echo "  Ollama:     http://localhost:11434 (RAG/LLM)"
  echo "              Pull a model: docker compose exec ollama ollama pull llama2"
  echo ""
  echo "  docker compose logs -f              # follow logs"
  echo "  docker compose logs -f ollama       # Ollama logs"
  echo ""

  # Wait for Ollama to be reachable (best-effort)
  if command -v curl &>/dev/null; then
    echo -n "Checking Ollama (RAG/LLM)... "
    n=0
    while [ $n -lt 30 ]; do
      if curl -s -o /dev/null -w "%{http_code}" http://localhost:11434/api/version 2>/dev/null | grep -q 200; then
        echo "up."
        break
      fi
      n=$((n + 1))
      if [ $n -eq 1 ]; then echo ""; fi
      echo "  waiting for Ollama ($n/30)..."
      sleep 2
    done
    if [ $n -ge 30 ]; then
      echo "  Ollama not responding yet. RAG will use stub until Ollama is ready. Pull a model: ollama pull llama2"
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
