# Cursor Plugin – Architecture and Changes

## Requirement 1: Intercept prompts (hook between LLM and IDE)

**Intended flow:** When the user sends a prompt in agent mode, Cursor invokes the `beforeSubmitPrompt` hook **before** calling the LLM. The hook should:

1. Receive the prompt payload (prompt text, `conversation_id`, `generation_id`, `workspace_roots`).
2. Call the backend: **ingest** (embedding → vector, search, graph, Redis) and **similar-responses** (RAG lookup).
3. Optionally short-circuit: if Cursor supported “return this response instead of calling the LLM,” we would use a similar cached response. **Cursor’s hook API does not support that** – only `continue: true` or `continue: false` (block with a message). So we cannot skip the LLM from the hook; we can only show similar results in the sidebar.
4. Always return `continue: true` so the agent runs; the sidebar shows “Last similar” from the hook’s similar-responses call.

**Why the hook might not run or backend might not be called:**

- **Hook not registered:** User must run “Enable Prompt Similarity integration” so `.cursor/hooks.json` contains `beforeSubmitPrompt` → our script. Cursor only runs hooks when they are in the **opened workspace’s** `.cursor/hooks.json`.
- **Wrong workspace:** The hook receives `workspace_roots` from Cursor. It uses `workspace_roots[0]` to read `.cursor/prompt-similarity.json`. If the user opened a different folder (e.g. the `cursor-plugin` folder instead of the project using the backend), that folder may not have `prompt-similarity.json`, so `loadConfig()` returns `null` and the hook exits without calling the backend.
- **Config file missing:** “Enable integration” writes `prompt-similarity.json` into the **first workspace folder**. If that folder was not the one where integration was enabled, or the file was deleted, the hook has no gateway URL and exits.
- **Gateway unreachable:** The hook runs on the user’s machine and calls `gatewayUrl` (e.g. `http://localhost:8080`). If the stack is not running or is on another host, ingest and similar-responses fail (we log and still return `continue: true`).

**Changes made:**

- **Diagnostics:** The hook now writes `.cursor/prompt-similarity-last-run.json` on every run with: `event`, `timestamp`, `configFound`, `ingestOk`, `similarOk`, `error`, `workspaceRoot`. The extension can show “Last hook run” and surface “config missing” or “ingest failed” so the user can fix setup.
- **Config lookup:** The hook tries `workspace_roots[0]`, then `workspace_roots[1]`, etc., then `process.cwd()`, so at least one workspace root with `.cursor/prompt-similarity.json` is used if available.
- **Verification:** User can confirm the hook is intercepting by: (1) seeing “Last hook run” in the extension with `ingestOk: true`, and (2) seeing prompts in the dashboard “All prompts” and traffic to embedding/vector/graph when the backend is running.

---

## Requirement 2: Prompt count metric

- **Backend:** Each ingest increments a **prompt count** (stored in Redis so it survives restarts). The **RAG stats** API (`GET /api/v1/prompts/rag/stats`) now returns `promptCount` (total prompts ingested).
- **Extension:** The status bar and sidebar show **“N prompts”** (and optionally “Last hook: X min ago”) so the user sees that every agent prompt is counted.

---

## Requirement 3: Prompt saved with timestamp (correlation foundation)

- **Already in place:** Every ingested prompt is stored with a **timestamp**: `PromptRepository` uses `createdAt = System.currentTimeMillis()` and persists it in Redis. The ingest payload includes `userId`, `orgId`, `text`; the backend stores `promptId`, `userId`, `orgId`, `text`, `createdAt`.
- **Cursor responses:** The `cursor-response` endpoint stores the agent response with `promptId`, so **prompt ↔ response correlation** is already possible.
- **Foundation for short-circuit:** Later you can query prompts by time range or similarity and match responses by `promptId` to build “fast-track” similar answers for other users. No additional schema change required for this foundation.

---

## Requirement 4: Unit tests for the plugin

- **Hook (Node):** Jest tests with mocked `fs` and `fetch`, covering: `loadConfig` (found / missing / invalid JSON), `handleBeforeSubmitPrompt` (ingest + similar-responses success/failure), writing last-run and last-similar files, and `respond(continue: true)`.
- **Extension (TypeScript):** Jest + `@vscode/test-cli` or mocked `vscode` module to test: `getConfig()`, `enableIntegration` (writes correct `.cursor/prompt-similarity.json` and `.cursor/hooks.json`), tree provider items (RAG stats, last similar), and that stats include `promptCount` and last-run when available.

---

## Summary of architecture changes

| Area | Change |
|------|--------|
| **Hook** | Write `.cursor/prompt-similarity-last-run.json` (event, configFound, ingestOk, similarOk, error, workspaceRoot). Try multiple workspace roots + cwd for config. |
| **Backend** | Prompt count in Redis; `GET /rag/stats` returns `promptCount`. Prompts already stored with timestamp (`createdAt`). |
| **Extension** | Fetch and display prompt count in status bar and sidebar; read and display “Last hook run” from last-run file. |
| **Tests** | Unit tests for hook (config, ingest, similar, last-run) and extension (config, enableIntegration, stats, prompt count). |
