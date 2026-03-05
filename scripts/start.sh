#!/usr/bin/env bash
# Start the Prompt Similarity application: ensure Docker is running, then build and run.
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Ensure Docker is running (start Docker Desktop on macOS if needed)
source "$ROOT/scripts/ensure-docker.sh"
ensure_docker || exit 1

echo ""
echo "=== Build (if needed) ==="
if [ ! -d "prompt-service/target/quarkus-app" ]; then
  echo "Building Java services..."
  docker run --rm -v "$ROOT:/app" -w /app maven:3.9-eclipse-temurin-17 mvn clean package -DskipTests -q
  echo "Java build done."
fi

if [ ! -d "dashboard/node_modules" ] || [ ! -d "dashboard/dist" ]; then
  echo "Building dashboard..."
  cd "$ROOT/dashboard"
  npm ci --silent 2>/dev/null || npm install --silent
  npm run build
  cd "$ROOT"
  echo "Dashboard build done."
fi

echo ""
echo "=== Start services ==="
docker compose up -d

echo ""
echo "Prompt Similarity is starting."
echo "  Gateway:   http://localhost:8080"
echo "  Embedding: http://localhost:8081"
echo "  Dashboard: serve from dashboard: cd dashboard && npm run dev  → http://localhost:3000"
echo ""
echo "  Logs: docker compose logs -f"
echo "  Stop: docker compose down"
