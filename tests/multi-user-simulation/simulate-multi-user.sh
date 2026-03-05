#!/usr/bin/env bash
# Simulate multiple users sending prompts to the gateway to test similarity and notifications
set -e
GATEWAY="${GATEWAY_URL:-http://localhost:8080}"

echo "Using gateway: $GATEWAY"
echo "User 1: Ingest prompt (JWT auth)..."
curl -s -X POST "$GATEWAY/api/v1/prompts/ingest" \
  -H "Content-Type: application/json" \
  -d '{"userId":"sim-user-1","orgId":"org1","text":"How do I implement JWT authentication in Spring Boot?","language":"en"}' | jq . || cat

echo ""
echo "User 2: Ingest similar prompt..."
curl -s -X POST "$GATEWAY/api/v1/prompts/ingest" \
  -H "Content-Type: application/json" \
  -d '{"userId":"sim-user-2","orgId":"org1","text":"I need to add JWT auth to my Spring Boot application","language":"en"}' | jq . || cat

echo ""
echo "User 3: Find similar..."
curl -s "$GATEWAY/api/v1/prompts/similar?text=Spring+Boot+JWT+security&orgId=org1&userId=sim-user-3&topK=5&minScore=0.5" | jq . || cat

echo ""
echo "Done. Check notification-service logs for similarity alerts."
