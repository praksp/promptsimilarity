# Cursor IDE ↔ Prompt Similarity Integration Plan

## Goal

Route Cursor IDE agent-mode interactions (user prompts and, where possible, agent responses) through the Prompt Similarity service so that:

1. **Every agent prompt** is ingested into the platform (vector DB, graph, Redis).
2. **Token savings** from the RAG system are visible inside the IDE (e.g. status bar or sidebar).
3. **Similar prompts** can be surfaced in the IDE when the user is about to send a prompt (optional for prototype).

---

## Current State

| Component | What exists |
|-----------|-------------|
| **Prompt Similarity backend** | API Gateway at `/api/v1/prompts`: `ingest`, `similar`, `live-similar`, `list`, `rag/similar-responses`, `rag/ask-llm`, `rag/record-satisfied`, `rag/stats`. |
| **Cursor plugin** | VS Code extension with commands: “Send current selection to Prompt Similarity” (`ingestPrompt`), “Find similar prompts” (`findSimilar`). Calls gateway for ingest and similar. **Does not** hook into agent mode automatically. |
| **Cursor agent** | User types in chat; Cursor sends to its own LLM and shows the response. No built-in extension API to read chat messages or completion events. |
| **Cursor Hooks** | Separate process invoked by Cursor on lifecycle events. Input: JSON on stdin. Output: JSON on stdout. Events include `beforeSubmitPrompt` (payload includes `prompt`, `conversation_id`, `generation_id`, etc.) and `stop` / `afterAgentResponse` (payload may include response; some encoding/behavior issues reported). |

---

## Integration Challenges

### 1. **No direct “chat completed” API in the extension**

- Extensions **cannot** subscribe to “user sent a prompt” or “agent finished replying” inside Cursor.
- So we **cannot** drive the flow purely from the VS Code extension.

**Mitigation:** Use **Cursor Hooks** (separate process) for agent lifecycle. The extension is used for **config**, **UI** (token stats, similar prompts), and **optional** “Enable integration” that installs the hook.

### 2. **Hooks run as a separate process**

- Hooks get JSON on stdin and must return JSON on stdout.
- They **do not** have access to VS Code/Cursor settings (e.g. `promptSimilarity.gatewayUrl`).
- So the hook needs its own way to get gateway URL and user/org id (env vars or a config file the extension can write).

**Mitigation:**  
- **Config file:** e.g. `.cursor/prompt-similarity.json` or `~/.cursor/prompt-similarity.json` with `gatewayUrl`, `userId`, `orgId`.  
- Extension command “Enable Prompt Similarity in this workspace” writes this file (and optionally `.cursor/hooks.json`).  
- Hook script (shipped with the extension) reads that config and calls the gateway.

### 3. **Hook script discovery**

- `hooks.json` points to a **command** (e.g. `node /path/to/hook.js`).
- The hook script must live somewhere stable. If it’s inside the extension, path is `context.extensionPath` (or `context.globalStorageUri`), which differs per install.

**Mitigation:**  
- Extension “Enable integration” command:  
  - Writes the project-level hook config (e.g. `.cursor/hooks.json`) with a command that invokes the hook script.  
  - Use an absolute path to the script: e.g. `node "${extensionPath}/dist/hook.js"` (extension path is known at runtime).  
- Alternatively, the extension copies the hook script into the workspace (e.g. `.cursor/scripts/prompt-similarity-hook.js`) and `hooks.json` points to that. Then the script is under user/version control and doesn’t depend on extension path.

### 4. **Token savings semantics in the IDE**

- Backend “token savings” today = when someone uses the **dashboard** and chooses a **cached similar response** instead of calling the LLM (our Ollama).
- In Cursor, the **LLM is Cursor’s**, not ours. We don’t call our `/rag/ask-llm` from the IDE.
- So we have two notions:
  - **A) Org/global RAG stats:** “Your org has saved X tokens by reusing similar answers” (from dashboard usage). **We can show this in the IDE** by calling `GET /rag/stats`.
  - **B) Per-conversation or “this session” savings in Cursor:** Would require Cursor to either use our LLM (not realistic) or to tell us how many tokens it used so we could compare to “if you had reused a similar prompt” (complex, and Cursor doesn’t expose token usage to extensions).

**Recommendation for prototype:**  
- Show **org/global RAG stats** in the IDE (total tokens saved, reuse count).  
- Optionally show “Similar prompts found for this conversation” (from last `beforeSubmitPrompt` run) so the user sees that the system is aware of similar context.

### 5. **afterAgentResponse / stop and encoding**

- Community reports suggest `afterAgentResponse` / `stop` payloads can have encoding issues (e.g. response text as `???`).
- We may not get a reliable “response text + token count” from Cursor.

**Recommendation for prototype:**  
- **Phase 1:** Use only `beforeSubmitPrompt`. We ingest the prompt and optionally fetch similar prompts. We do **not** depend on Cursor sending the agent response.  
- **Phase 2 (optional):** Add `stop` or `afterAgentResponse`; if the payload includes response text, we can send it to the backend (e.g. a new “record Cursor response” endpoint) for analytics; if not, we skip or retry later when Cursor fixes encoding.

### 6. **CORS and network**

- The hook runs on the user’s machine and calls the gateway (e.g. `http://localhost:8080`). No CORS issue for a Node script.
- The extension’s frontend (if we use a webview) also calls the gateway; if the dashboard is any indication, we’ll call the same base URL. If the gateway allows the Cursor origin or we use a simple GET with no custom headers, we’re fine; otherwise we may need gateway CORS or a proxy. For a prototype, same-origin or localhost is enough.

### 7. **Offline / gateway down**

- If the gateway is down, the hook should not block the user. Hook must:
  - Call the gateway with a short timeout.
  - On failure: log (or write to a small log file for debugging) and **return `{"continue": true}`** so Cursor continues normally.

---

## Recommended Architecture for a Working Prototype

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Cursor IDE                                                             │
│  ┌──────────────────────┐    ┌─────────────────────────────────────┐  │
│  │  Agent chat UI       │    │  Prompt Similarity extension         │  │
│  │  (user types prompt) │    │  - Status bar: "RAG: X tokens saved"  │  │
│  └──────────┬───────────┘    │  - Sidebar / panel: stats + similar  │  │
│             │                 │  - Config: gateway, userId, orgId    │  │
│             │ submit          │  - Command: "Enable integration"    │  │
│             ▼                 │    → writes .cursor/hooks.json +     │  │
│  ┌──────────────────────┐    │      .cursor/prompt-similarity.json  │  │
│  │  Cursor Hooks        │    └─────────────────────────────────────┘  │
│  │  beforeSubmitPrompt  │                      ▲                        │
│  │  → run hook process  │                      │ read config          │
│  └──────────┬──────────┘                      │ (gateway, userId)    │
│             │                                  │                      │
│             │ stdin (JSON)                     │                      │
│             ▼                                  │                      │
│  ┌──────────────────────┐    ┌────────────────┴────────────────────┐  │
│  │  Hook script         │    │  .cursor/prompt-similarity.json       │  │
│  │  (Node or shell)     │    │  { gatewayUrl, userId, orgId }      │  │
│  │  - Parse prompt      │    └─────────────────────────────────────┘  │
│  │  - POST /ingest      │                                              │
│  │  - POST /rag/        │                                              │
│  │    similar-responses │                                              │
│  │  - stdout: continue │                                              │
│  └──────────┬──────────┘                                              │
└─────────────┼─────────────────────────────────────────────────────────┘
              │ HTTP
              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Prompt Similarity (existing)                                            │
│  API Gateway :8080 → prompt-service → vector, graph, Redis, etc.         │
│  GET /rag/stats  → tokens saved (org), reuse count                      │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Data Flow (Prototype)

1. **User enables integration (once)**  
   - Runs extension command “Enable Prompt Similarity integration”.  
   - Extension writes:  
     - `.cursor/prompt-similarity.json`: `{ "gatewayUrl": "...", "userId": "...", "orgId": "..." }` (from extension settings).  
     - `.cursor/hooks.json`: one hook for `beforeSubmitPrompt` that runs the hook script (path from extension or workspace).

2. **User sends a prompt in agent chat**  
   - Cursor runs the `beforeSubmitPrompt` hook.  
   - Hook receives JSON with `prompt`, `conversation_id`, `generation_id`.  
   - Hook reads `.cursor/prompt-similarity.json` (or env) for gateway and identity.  
   - Hook calls:  
     - `POST /api/v1/prompts/ingest` (userId, orgId, text = prompt).  
     - `POST /api/v1/prompts/rag/similar-responses` (same payload) to get similar prompts/responses.  
   - Hook optionally writes “last similar” to a small file (e.g. `.cursor/last-similar.json`) for the extension to show.  
   - Hook returns `{"continue": true}` so the prompt is sent to Cursor’s model as usual.

3. **Token savings in the IDE**  
   - Extension (on activation or when opening the view) calls `GET /api/v1/prompts/rag/stats?orgId=...`.  
   - Shows “Tokens saved: X” (and optionally “Reuses: Y”) in status bar or a sidebar panel.  
   - Can refresh on interval or on a “Refresh” button.

4. **Optional: “Similar prompts” in IDE**  
   - If the hook wrote `.cursor/last-similar.json`, the extension can read it and show “Similar prompts for last submission” in a webview or tree.  
   - Or the extension can call `GET /api/v1/prompts/similar?text=...` when the user runs “Find similar” (already exists) or when opening the panel (using the last prompt from the hook file).

---

## Implementation Phases

### Phase 1 – Minimal working prototype (recommended first)

| Step | Task | Owner / notes |
|------|------|-------------------------------|
| 1.1 | Add **hook script** (Node.js) that: reads stdin JSON; if event is `beforeSubmitPrompt`, reads config from `.cursor/prompt-similarity.json` or env; calls `POST /ingest` and `POST /rag/similar-responses`; returns `{"continue": true}`; on any error, still returns `continue: true` and logs. | Ship script in extension (e.g. `scripts/prompt-similarity-hook.js`) or in repo under `.cursor/scripts/`. |
| 1.2 | Extension: add command **“Enable Prompt Similarity integration”** that (a) reads `gatewayUrl`, `userId`, `orgId` from workspace config, (b) ensures `.cursor` exists, (c) writes `prompt-similarity.json`, (d) writes or merges `hooks.json` with `beforeSubmitPrompt` → hook script. Use extension path for script if script lives in extension. | Extension |
| 1.3 | Extension: add **status bar item** or **sidebar view** that calls `GET /rag/stats?orgId=...` and displays “RAG: X tokens saved (Y reuses)”. Handle offline: show “Prompt Similarity: offline” or hide. | Extension |
| 1.4 | Document in README: enable integration (one-time), ensure gateway is running, meaning of “tokens saved” (org-level from dashboard RAG usage). | Docs |

### Phase 2 – Optional enhancements

| Step | Task |
|------|------|
| 2.1 | Hook writes “last similar” result to `.cursor/last-similar.json`. Extension panel shows “Similar prompts for last agent prompt” from that file. |
| 2.2 | Add `stop` or `afterAgentResponse` hook: if payload contains response text, call a new backend endpoint to record “Cursor response” for analytics (no token savings from Cursor’s LLM unless we define a new metric). |
| 2.3 | Extension: “Disable integration” command that removes or disables the hook entry from `hooks.json`. |

---

## File Layout (suggested)

```
cursor-plugin/
  src/
    extension.ts          # existing + new commands + status bar / panel
  scripts/                # or resources/
    prompt-similarity-hook.js   # hook entry (Node; reads stdin, calls gateway)
  package.json            # new commands, views, config
  docs/
    INTEGRATION.md        # user-facing: how to enable, what tokens saved means
```

User workspace after “Enable integration”:

```
.cursor/
  hooks.json              # { "hooks": { "beforeSubmitPrompt": [ { "command": "node .../prompt-similarity-hook.js" } ] } }
  prompt-similarity.json  # { "gatewayUrl": "http://localhost:8080", "userId": "...", "orgId": "..." }
  last-similar.json       # optional, written by hook for extension to show
```

---

## Summary and Recommendation

- **Use Cursor Hooks** to get agent prompts into the Prompt Similarity service; **use the existing extension** for config, token stats UI, and optional “similar prompts” display.
- **Prototype scope:**  
  - Hook on `beforeSubmitPrompt` only: ingest + similar-responses; always return `continue: true`.  
  - Extension: “Enable integration” (writes hook config + prompt-similarity.json), status bar or panel with `GET /rag/stats` (tokens saved, reuses).  
- **Token savings in the IDE** = same as dashboard: org-level RAG impact (tokens saved when someone reused a similar answer). No per-Cursor-session token count unless we add a new backend metric and a way to get Cursor’s token usage (not available today).
- **Risks:** Hook script path and config location must be robust; hook must never block the user (timeout + `continue: true` on failure).  
- **Next step:** Implement Phase 1 (hook script, “Enable integration” command, stats in status bar or panel), then iterate with Phase 2 if needed.

---

## Implementation status (branch: feature/cursor-plugin-integration)

- **Phase 1:** Hook script (`scripts/prompt-similarity-hook.js`), Enable/Disable integration commands, status bar RAG stats, sidebar view (RAG impact + Last similar), config file `.cursor/prompt-similarity.json`, hooks.json with `beforeSubmitPrompt` and `stop`.
- **Phase 2 (optional):** Hook writes `last-similar.json`; extension shows it in the sidebar; `stop` hook calls `POST /cursor-response`; backend endpoint `POST /api/v1/prompts/cursor-response` added (gateway + prompt-service).
- **Docs:** `cursor-plugin/INTEGRATION.md`, README section 6.
