# Persistence and data

## What persists across restarts

| Data | Storage | Survives `docker compose down` / restart? |
|------|---------|------------------------------------------|
| **Prompts** (ingested from Cursor or dashboard) | Redis (`redis-data` volume) | **Yes** – prompt-service loads from Redis on startup. |
| **Feature-store** features | Redis | **Yes** (same Redis). |
| **Ollama models** (e.g. llama2) | Named volume `ollama-models` | **Yes** – pull once with `docker compose exec ollama ollama pull llama2`; no need to re-pull after restart. |
| **Vector index** (embeddings for similarity) | In-memory in vector-service | **No** – restart wipes it. Prompts are re-ingested into Redis but vector/search need to be repopulated by new ingest calls. |
| **Search index** | In-memory in search-service | **No**. |
| **Graph** (prompt/response nodes and edges) | Redis (`redis-data` volume) in graph-service | **Yes** – graph-service loads from Redis on startup and writes after each change. |
| **RAG response cache** (stored LLM responses) | In-memory in prompt-service | **No** – after restart, similar-responses won’t return cached answers until new responses are stored. |
| **Token-saved stats** | In-memory in prompt-service | **No**. |

So: **prompts** (Redis), **graph** (Redis), and **Ollama models** (volume) persist. Vector, search, RAG cache, and token stats are in-memory and are lost on restart until the stack is used again (new ingests repopulate vector/search).

---

## Plugin and dashboard share the same backend

The **Cursor plugin** (and its hook) and the **dashboard** both talk to the same **API gateway** (`/api/v1/prompts`). So they use the same prompt repository, vector DB, graph DB, and RAG stats. The dashboard now shows the **Prompts (total)** counter from `GET /rag/stats`. If Cursor prompts don't appear in the dashboard list, the hook is likely not ingesting. To confirm the backend is wired correctly, run:

```bash
./scripts/validate-plugin-backend.sh
```

---

## Why Cursor prompts might not show in the dashboard

1. **“Load prompts on demand” is off**  
   In the dashboard, open **“All prompts in the system”** and check the **“Load prompts on demand”** checkbox. The list is only fetched when that is enabled.

2. **Cursor integration not enabled**  
   In Cursor, run **“Enable Prompt Similarity integration”** (Command Palette or plug icon in the Prompt Similarity view). This writes `.cursor/hooks.json` and `.cursor/prompt-similarity.json`. Without this, the hook does not run and no prompts are sent to the backend.

3. **Gateway not reachable from the hook**  
   The hook runs on your machine and calls `promptSimilarity.gatewayUrl` (default `http://localhost:8080`). If the stack runs in Docker on the same machine, `localhost:8080` is correct. If the stack runs elsewhere, set `promptSimilarity.gatewayUrl` in Cursor settings (e.g. `http://<host>:8080`).

4. **Wrong workspace**  
   The hook reads config from the **first workspace root** in the payload. If you opened a different folder, that folder’s `.cursor/prompt-similarity.json` is used; if it’s missing or points to another URL, ingest may fail or go to another backend.

5. **Ingest failing silently**  
   The hook always returns `continue: true` so Cursor keeps going even if ingest fails (e.g. timeout, connection refused). Check that the stack is up (`curl -s http://localhost:8080/q/health`) and that no firewall blocks the hook’s outbound request to the gateway.

---

## Why the plugin might not short-circuit (all prompts going to the agent)

1. **Hook not running or config not found**  
   If `.cursor/prompt-similarity.json` is missing or the hook’s workspace roots don’t include the folder that has it, the hook exits with `continue: true` and never calls similar-responses or ingest. Check `.cursor/prompt-similarity-last-run.json`: `configFound` should be `true`.

2. **First response never stored**  
   Short-circuit only works when a previous run stored a response. That requires: (a) first prompt was ingested (hook ran, ingest succeeded), (b) agent ran and produced a reply, (c) **stop** hook ran and sent `cursor-response` with the same `promptId`. If the stop hook didn’t run or the workspace/promptId didn’t match, the backend has no cached response for that prompt.

3. **Vector store empty**  
   Vector index is in-memory. After a backend restart, there are no embeddings until new prompts are ingested. So the first prompt after restart always goes to the agent; the second identical prompt will short-circuit only after the first one’s response is stored via cursor-response.

4. **Similarity threshold**  
   The backend returns similar-responses only for matches with score ≥ 0.65. The hook short-circuits when score ≥ 0.5. So if the backend returns a match, the hook will short-circuit. If the backend returns no match (e.g. different phrasing, or no stored response for that promptId), the agent runs.

---

## Ollama: pull model once, then it persists

- **First time** (or on a new machine):  
  `docker compose exec ollama ollama pull llama2`  
  (or another model). The model is stored in the `ollama-models` volume.

- **After restart**  
  The same container (or a new one) uses the same volume, so the model is already there. You do **not** need to run `ollama pull` again unless you remove the volume or use a new environment.

- **Optional automation**  
  You can add a one-time pull to your startup script, e.g. after `docker compose up -d`, run `docker compose exec ollama ollama list` and if the model is missing, run `ollama pull llama2`. The image has no `curl`, so health checks from inside the container are limited; pulling from the host after compose up is the usual approach.
