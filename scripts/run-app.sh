#!/usr/bin/env bash
# Run the Prompt Similarity application.
# Prerequisites:
#   - Docker running (for full stack) OR Java 17 + Maven (for local dev)
#   - For embedding service only: Python 3.10+ with pip

set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=== Prompt Similarity: Run Application ==="

# 1) Build Java services if needed (for Docker Compose)
if docker info &>/dev/null; then
  if [ ! -d "prompt-service/target/quarkus-app" ]; then
    echo "Building Java services with Maven (using Docker)..."
    docker run --rm -v "$ROOT:/app" -w /app maven:3.9-eclipse-temurin-17 mvn clean package -DskipTests -q
    echo "Build done."
  else
    echo "Java build already present (prompt-service/target/quarkus-app). Skip build. Delete target/ to rebuild."
  fi

  echo "Starting all services with Docker Compose..."
  docker compose up -d
  echo ""
  echo "Services starting. Gateway: http://localhost:8080  |  Embedding API: http://localhost:8081"
  echo "Check status: docker compose ps"
  echo "Logs: docker compose logs -f"
  exit 0
fi

# 2) Docker not available: run embedding service locally (no Java backend)
echo "Docker is not running. Using embedding service only (Python)."
echo "For the full stack: start Docker Desktop and run this script again."
echo ""

if curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/health 2>/dev/null | grep -q 200; then
  echo "Embedding service is already running at http://localhost:8081"
  echo "Health: $(curl -s http://localhost:8081/health)"
  exit 0
fi

if ! command -v python3 &>/dev/null; then
  echo "python3 not found. Install Python 3.10+ and re-run."
  exit 1
fi

cd "$ROOT/embedding-service"
if [ ! -d "venv" ]; then
  echo "Creating venv and installing dependencies..."
  python3 -m venv venv
  ./venv/bin/pip install -r requirements.txt -q
fi
echo "Starting embedding service on http://localhost:8081 ..."
exec ./venv/bin/uvicorn main:app --host 0.0.0.0 --port 8081
