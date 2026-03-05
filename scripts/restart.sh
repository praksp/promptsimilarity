#!/usr/bin/env bash
# Restart the Prompt Similarity application: ensure Docker is running, stop containers, rebuild, start.
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Ensure Docker is running
source "$ROOT/scripts/ensure-docker.sh"
ensure_docker || exit 1

echo ""
echo "=== Stop existing containers ==="
docker compose down 2>/dev/null || true

echo ""
echo "=== Clean build ==="
rm -rf proto/target api-gateway/target prompt-service/target vector-service/target
rm -rf search-service/target feature-store-service/target notification-service/target
rm -rf collaboration-service/target graph-service/target dashboard/dist

echo "Building Java services..."
docker run --rm -v "$ROOT:/app" -w /app maven:3.9-eclipse-temurin-17 mvn clean package -DskipTests -q
echo "Java build done."

echo "Building dashboard..."
cd "$ROOT/dashboard"
npm ci --silent 2>/dev/null || npm install --silent
npm run build
cd "$ROOT"
echo "Dashboard build done."

echo ""
echo "=== Start services ==="
docker compose up -d

echo ""
echo "Prompt Similarity restarted."
echo "  Gateway:   http://localhost:8080"
echo "  Embedding: http://localhost:8081"
echo "  Dashboard: cd dashboard && npm run dev  → http://localhost:3000"
echo ""
echo "  Logs: docker compose logs -f"
