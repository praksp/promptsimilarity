## Prompt Similarity Platform – Technical Learnings

This document captures the key technical mistakes, corrective changes, and forward-looking recommendations from building and stabilizing the distributed prompt similarity platform. It is meant to be **language- and framework-agnostic guidance** for similar systems.

---

### 1. Protocol & Framework Compatibility

**What went wrong**

- Mixed gRPC, HTTP/1.1, HTTP/2, and framework-specific modes without a clear, validated design.
- The API gateway started as a gRPC client, then moved toward HTTP/2 unified servers, then to REST, but Docker and configuration often lagged.
- `prompt-service` expected gRPC on one port while backends were actually serving plain HTTP or misconfigured HTTP/2, causing `UNIMPLEMENTED` errors and HTML 404 responses to gRPC calls.

**Fixes**

- **Gateway ↔ Prompt Service**: Standardized on **HTTP REST** with a typed REST client. All gRPC usage was removed from the gateway.
- **Prompt Service ↔ Backends** (vector/search/graph/feature-store/notification/collaboration):
  - Standardized on **classic gRPC with dedicated ports**.
  - Each backend now runs:
    - HTTP on a well-known port (for health/future REST).
    - A **separate gRPC server** on a dedicated port (e.g. 9002/9003/…).
  - `prompt-service`’s gRPC client configuration (`GrpcChannels`) and environment variables point explicitly to those gRPC ports.
- **Proto module**:
  - Migrated from legacy generators and `javax.annotation-api` to Quarkus’ `generate-code` and `jakarta.annotation-api`, aligning codegen with the runtime framework.

**Future guidance**

- **Choose one protocol per boundary and commit to it**:
  - Edge (UI/plugin ↔ backend) = REST/HTTP.
  - Internal (service ↔ service) = gRPC or another RPC, but consistent.
- When a framework has multiple modes (e.g., separate gRPC server vs. unified HTTP/2):
  - Read the official docs first and pick one pattern deliberately.
  - Avoid flipping back and forth mid-implementation.
- For IDL/codegen-based contracts:
  - Keep generator, runtime, and framework versions aligned.
  - Treat the proto/IDL module as a **first-class artifact**; build and verify it before dependent modules.

---

### 2. Docker, Ports, and Healthchecks

**What went wrong**

- Containers showed as “Up (healthy)” but were not serving what clients expected.
- Healthchecks probed the wrong port or protocol (e.g., HTTP `/q/health` on a gRPC-only port, or `nc` on an HTTP-only port).
- `prompt-service` attempted to reach gRPC services on 8080, while those services were not actually serving gRPC there.

**Fixes**

- Adopted a clear mapping:
  - Backends: HTTP on 8080, **gRPC on dedicated ports** (9002–9007).
  - Docker: `ports: "900X:900X"` so host/container ports match for gRPC.
  - Healthchecks for gRPC services: use simple TCP checks (e.g. `nc -z localhost 9002`) rather than assuming REST endpoints.
- Updated `prompt-service` environment variables to use these dedicated gRPC ports.

**Future guidance**

- **One role per port**: decide which port is HTTP and which is gRPC, then reflect that consistently in:
  - Service configuration.
  - Docker port mappings.
  - Healthchecks and monitoring.
- Do not rely solely on container “health”:
  - Design healthchecks that validate the same protocol and port the real clients use, or at minimum the same process.

---

### 3. Build & Deployment Discipline

**What went wrong**

- Containers were often built from **stale artifacts**, so new code ran against old JARs.
- Proto module was not always rebuilt before modules that depended on its generated stubs.

**Fixes**

- Standardized on a **root-level build**:
  - Use Maven (or equivalent) from the project root so all modules, including the proto module, are rebuilt in the proper order.
- Updated the main `clean-build-run` script to:
  - Clean module targets.
  - Build all Java services (preferably in Dockerized Maven).
  - Build the dashboard.
  - Run `docker compose build --no-cache` so images always reflect the latest build.

**Future guidance**

- Treat “code vs. container” drift as a systemic risk:
  - For non-trivial backend changes, always run: **clean build → rebuild images → start stack**.
- For multi-module projects:
  - Prefer building from the **root** rather than individual submodules.
- In constrained environments (no local build tools):
  - Adopt a single **containerized build pipeline** that developers and CI both use.

---

### 4. Error Handling, Timeouts, and Resilience

**What went wrong**

- gRPC calls initially had no explicit deadlines, leading to long `DEADLINE_EXCEEDED` errors.
- Error messages surfaced low-level details (raw exceptions) instead of actionable descriptions.
- As resilience logic (`onFailure().recoverWithItem`) was added, some real configuration bugs were masked by graceful fallbacks.

**Fixes**

- Added explicit **deadlines** on all gRPC stub calls (e.g., 90 seconds).
- Introduced a central error mapper at the edge (gateway) that:
  - Detects timeout and connection-failure patterns.
  - Returns user-friendly messages while still logging root causes.
- Applied **layered resilience**:
  - Service clients catch and log failures, then return safe defaults (e.g. empty lists).
  - REST resources and the gateway apply a final recovery layer to avoid 500s on purely “auxiliary” features like similarity search.

**Future guidance**

- Always set explicit timeouts/deadlines for network calls and document why each value was chosen.
- Separate:
  - **User-facing behavior** (friendly messages, fallbacks).
  - **Internal observability** (logging, metrics, alerts for the real error).
- Be cautious with “swallow all failures” patterns:
  - Use them where degraded behavior is acceptable, but still ensure errors are visible to operators.

---

### 5. Testing Strategy (Unit, Integration, E2E)

**What went wrong**

- Some changes were declared “fixed” before running specific tests that validated the intended behavior.
- Tests didn’t initially prove that:
  - The gateway really used REST instead of gRPC.
  - E2E flows returned the correct HTTP status codes and payload shapes.

**Fixes**

- **Integration tests**:
  - Added a test profile and WireMock-based backend for the gateway to prove that it calls `prompt-service` via HTTP REST.
  - Updated expectations when resilience changed behavior (from 500 to 200 + empty list).
- **E2E script**:
  - Enforced HTTP 200 (not 500) for ingest and findSimilar.
  - Validated presence of `promptId`.
  - Added a list endpoint check to confirm prompts persist end to end.

**Future guidance**

- For any cross-service change, add:
  - A focused test proving the new integration behavior in isolation (mocking downstream services).
  - An E2E check simulating real traffic against the running stack.
- When you change resilience/HTTP semantics, update tests immediately; mismatched expectations are a red flag.

---

### 6. Observability & Debugging

**What went wrong**

- Early on, there was little visibility into what was being stored or searched in the vector service.
- The UI did not show whether prompts were actually persisted, making it hard to differentiate “no similarity” from “data never stored”.

**Fixes**

- Logging:
  - Service clients log key operations (store/search) and the number of matches.
  - Vector service logs:
    - Query metadata (size, org, exclude user, threshold).
    - Total prompts stored.
    - Cosine scores for candidate matches.
- UI:
  - Added a “Recent prompts” widget to show all prompts stored, including user and timestamp.
  - Enhanced similarity views to show `textPreview` and better empty-state messages.
- Scripts:
  - E2E script prints partial response bodies for failures to quickly reveal backend behavior.

**Future guidance**

- For critical flows (e.g., embedding + vector search), log:
  - Inputs (safely, respecting privacy).
  - Derived values (scores, thresholds).
  - Outcomes (matches/no matches).
- Provide UX-level observability where possible (simple admin/debug widgets can save hours).

---

### 7. Data Semantics (Thresholds, IDs, Orgs)

**What went wrong**

- Initial similarity threshold was too high relative to actual cosine scores for realistic user prompts.
- Vector filtering logic assumed certain fields (like `excludeUserId`) were always non-null.

**Fixes**

- Lowered the default similarity threshold (while keeping it configurable).
- Fixed filters to handle null/empty fields correctly.
- Stored and propagated `text_preview` metadata end-to-end so similarity results are explainable to users.

**Future guidance**

- Treat thresholds as **configuration**, not hard-coded constants.
  - Expose them via configuration and document recommended ranges.
- Validate default behavior with realistic scenarios and sample data, not just synthetic cases.
- Make results explainable:
  - Include previews, scores, and relevant metadata wherever it improves understanding.

---

### 8. API Contracts & Cross-Service Design

**What went wrong**

- Some assumptions between services were implicit (e.g., gateway assuming gRPC where REST was more appropriate).
- New fields (like `text_preview`) were introduced but not always traced through every layer immediately.

**Fixes**

- Clarified boundaries:
  - Edge APIs: REST/JSON, DTOs tailored for UI/plugin consumption.
  - Internal APIs: gRPC/proto contracts for service-to-service communication.
- Ensured that new fields flow end to end:
  - proto → generated types → service clients → orchestrator → REST DTOs → gateway DTOs → UI types.

**Future guidance**

- For each boundary, define:
  - Protocol (REST, gRPC, etc.).
  - Ownership of schemas/IDLs.
  - Versioning/compatibility strategy.
- When expanding contracts, trace new fields through:
  - Schemas/protos.
  - Generated code.
  - Service logic.
  - Gateways and clients.
  - UI models and tests.

---

### 9. Process & Mindset

**What went wrong**

- Broad, simultaneous changes (protocol, ports, resilience) were made without enough incremental validation.
- Assumptions persisted even after repeated contradictory symptoms (e.g., “healthy” containers with broken gRPC).

**Fixes in approach**

- Narrowed hypotheses for each symptom:
  - Is the prompt stored?
  - Is the vector stored?
  - Is search being called?
  - What are the actual scores vs. threshold?
- Introduced small, targeted tests and scripts for each suspected failure point.

**Future guidance**

- Make progress in **small, verifiable steps**:
  - Change one major axis at a time (e.g., protocol, port layout, threshold).
- For every bug fixed, capture:
  - A failing test or script before.
  - A passing one after.
- Optimize for **debuggability before micro-optimizations**:
  - A system that fails loudly with clear evidence is vastly easier to operate and evolve than one that silently “works” but produces empty or confusing results.

---

### 10. Plugin, Dashboard, and UI Iterations

**What went wrong**

- Cursor plugin hooks did not run because `.cursor/prompt-similarity.json` and `.cursor/hooks.json` were empty or missing; users saw no ingest/similar-responses and assumed the plugin was broken.
- Tokens-saved and reuse metrics were not updated when reusing a cached response (dashboard “Use this” or plugin short-circuit) when the stored response had `tokensUsed = 0` (e.g. from `cursor-response`).
- Dashboard and plugin showed different metrics or stale numbers because the dashboard did not poll RAG stats and each client could point at different gateways or orgs.
- UI changes (layout, theme, graph) were done in large sweeps; form-like top-to-bottom layout required heavy scrolling and the similarity graph showed “user as node, prompt as edge,” which was harder to read than “prompt + user as nodes, edge = similar prompt.”
- Banner and section backgrounds used a different variable (`--banner-bg`) that did not match the rest of the dark theme.

**Fixes**

- **Config and hooks**: Documented that both `.cursor/prompt-similarity.json` (gatewayUrl, userId, orgId) and `.cursor/hooks.json` (beforeSubmitPrompt, stop, afterAgentResponse) must contain valid JSON. Extension “Enable Prompt Similarity integration” writes both; if files exist but are size 0, overwrite them by re-running the command or populating manually. Added `.cursor/` to `.gitignore` so machine-specific hook paths are not committed.
- **Tokens saved on reuse**: When recording feedback for a cached response, if `tokensUsed <= 0`, the backend now estimates tokens from response text length (e.g. chars/4) so “tokens saved” and “this month” increment instead of staying zero.
- **Dashboard–plugin consistency**: Same gateway URL and orgId for both; dashboard polls `GET /rag/stats` every 30s so plugin-driven reuses show up. Documented in `DASHBOARD_PLUGIN_CONSISTENCY.md`.
- **Full-viewport layout**: Replaced long form layout with a single 100vh canvas: CSS Grid with banner (fixed), then main grid (RAG full width, prompt full width, similarity + prompts in two columns, find-similar full width). Sections use `min-height: 0` and `overflow: auto` so content scrolls inside cells and the whole app fits in one view.
- **Similarity graph**: Switched to **nodes = prompts and users**; **edges = “similar prompt”** between a prompt node and a user node. Edge length encodes similarity (higher score = shorter). Edge color: green (high), yellow (mid), orange (low). Center shows “Your prompt” and current user; similar users and their prompts arranged around with colored edges.
- **Banner**: Use `var(--surface)` for the banner background so it matches the rest of the page; removed a separate `--banner-bg` that looked out of place.

**Future guidance**

- For any “integration” that depends on local config files, validate that files exist and are non-empty and that the runner (e.g. Cursor) invokes the hook with the expected workspace roots. Provide a one-command or one-click “Enable” that writes both structure and content.
- When two UIs (dashboard and plugin) display the same backend metrics, use one source of truth (same API, same org) and refresh the dashboard periodically so plugin actions are visible without a manual reload.
- Design graph visualizations so node/edge semantics match user mental models (e.g. “prompt and user as nodes” with “similar prompt” as the relationship and visual encoding for strength).

---

These learnings apply across languages and frameworks. The core themes are:

- Decide and document protocols and boundaries early.
- Keep builds, containers, and configuration in sync.
- Combine resilience with observability, not instead of it.
- Validate behavior with realistic tests at the unit, integration, and E2E levels.
- Design for transparency so users and operators can understand what the system is doing and why.

