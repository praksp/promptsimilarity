#!/usr/bin/env bash
# Validate that the Prompt Similarity backend is wired correctly for the Cursor plugin:
# - Ingest stores prompts in the same repo the dashboard list uses
# - cursor-response stores the agent reply for RAG
# - similar-responses returns cached replies when available
# - rag/stats includes promptCount (same metrics as plugin/dashboard)
# Run with the stack up: ./scripts/validate-plugin-backend.sh [GATEWAY_URL]
set -e
GATEWAY="${1:-http://localhost:8080}"
GATEWAY="${GATEWAY%/}"
API="${GATEWAY}/api/v1/prompts"
USER_ID="validate-user"
ORG_ID="default-org"
PROMPT_1="whats the weather in seattle"
PROMPT_2="what is the weather in seattle"

echo "=== Validate Plugin Backend ==="
echo "Gateway: $GATEWAY"
echo ""

# 1) Health
echo "1. Gateway health..."
if ! curl -sf "${GATEWAY}/q/health" >/dev/null; then
  echo "   FAIL: Gateway not reachable at $GATEWAY. Start the stack (e.g. ./scripts/clean-build-run.sh)."
  exit 1
fi
echo "   OK"
echo ""

# 2) Ingest a prompt (simulates hook ingest)
echo "2. Ingest prompt (simulate plugin ingest)..."
INGEST_JSON=$(curl -sf -X POST "${API}/ingest" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"${USER_ID}\",\"orgId\":\"${ORG_ID}\",\"text\":\"${PROMPT_1}\",\"language\":\"en\"}")
PROMPT_ID=$(echo "$INGEST_JSON" | grep -o '"promptId":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$PROMPT_ID" ]; then
  echo "   FAIL: Ingest did not return promptId. Response: $INGEST_JSON"
  exit 1
fi
echo "   OK promptId=$PROMPT_ID"
echo ""

# 3) List prompts (same endpoint dashboard uses) — should include the one we just ingested
echo "3. List prompts (dashboard /list)..."
LIST_JSON=$(curl -sf "${API}/list")
if ! echo "$LIST_JSON" | grep -q "$PROMPT_ID"; then
  echo "   FAIL: Ingested prompt not found in list. Plugin and dashboard share the same repo; if Cursor prompts don't show, the hook may not be running or not finding config."
  echo "   List sample: ${LIST_JSON:0:200}..."
  exit 1
fi
echo "   OK (ingested prompt appears in list)"
echo ""

# 4) Store a response for that prompt (simulates hook cursor-response after agent reply)
echo "4. Store agent response (simulate plugin cursor-response)..."
curl -sf -X POST "${API}/cursor-response" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"${USER_ID}\",\"orgId\":\"${ORG_ID}\",\"promptId\":\"${PROMPT_ID}\",\"responseText\":\"Seattle weather: cloudy, 52°F. (validation script)\"}" >/dev/null
echo "   OK"
echo ""

# 5) similar-responses (what the hook calls first) — should return the cached response
echo "5. similar-responses (hook uses this to short-circuit)..."
SIMILAR_JSON=$(curl -sf -X POST "${API}/rag/similar-responses" \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"${PROMPT_2}\",\"userId\":\"${USER_ID}\",\"orgId\":\"${ORG_ID}\"}")
if ! echo "$SIMILAR_JSON" | grep -q "Seattle weather"; then
  echo "   WARN: similar-responses did not return the cached response. Possible causes:"
  echo "   - Backend similarity threshold is 0.65; prompt text must be close enough."
  echo "   - Vector/embedding service may be slow or failing."
  echo "   Response sample: ${SIMILAR_JSON:0:300}..."
else
  echo "   OK (cached response returned; hook would short-circuit with this)"
fi
echo ""

# 6) RAG stats (plugin and dashboard should show same numbers)
echo "6. RAG stats (plugin + dashboard use this)..."
STATS_JSON=$(curl -sf "${API}/rag/stats?orgId=${ORG_ID}")
echo "   $STATS_JSON"
if ! echo "$STATS_JSON" | grep -qE '"promptCount"|"prompt_count"'; then
  echo "   WARN: promptCount not in stats; dashboard/plugin may not show prompt count."
fi
echo ""

echo "=== Validation done ==="
echo "If all steps passed: backend is correct. If Cursor prompts still don't show or never short-circuit:"
echo "  - Ensure integration is enabled (Prompt Similarity view → Enable integration)."
echo "  - Ensure the workspace you have open in Cursor is the one that contains .cursor/prompt-similarity.json."
echo "  - Check .cursor/prompt-similarity-last-run.json after sending a prompt; configFound should be true, ingestOk/similarOk true."
