# Dashboard and plugin consistency

The **dashboard** and **Cursor plugin** are designed to show the same data and metrics. They share one backend (the API gateway and prompt-service).

## Same backend, same metrics

- **Gateway:** Both must talk to the same base URL (e.g. `http://localhost:8080`).
  - **Dashboard:** Uses Vite proxy in dev (`/api` → `http://localhost:8080`) or `VITE_API_BASE_URL` when set. So effectively `http://localhost:8080` when running `npm run dev` with default proxy.
  - **Plugin:** Uses `promptSimilarity.gatewayUrl` (default `http://localhost:8080`). Set it to the same URL the dashboard uses so both hit the same gateway.
- **Org:** Both use the same org for stats and RAG. Default is `default-org`.
  - **Dashboard:** `ORG_ID = 'default-org'` in each component that needs it.
  - **Plugin:** `promptSimilarity.orgId` (default `default-org`).

When gateway and org match, **tokens saved**, **reuse count**, **prompt count**, and **this month** are the same in both UIs because they come from the same API: `GET /api/v1/prompts/rag/stats?orgId=...`.

## What updates the numbers

- **Reuse / “Use this” (dashboard):** User submits a prompt, sees similar responses, clicks “Use this” → frontend calls `POST /rag/feedback` and `POST /rag/record-satisfied` → backend updates tokens saved and reuse count. Dashboard refreshes stats via `onRagImpact`.
- **Reuse (plugin – Live Similar panel):** User types in Live Similar, clicks “Use this (cached)” → extension calls the same `rag/feedback` and `rag/record-satisfied` → same backend counters. Plugin status bar refreshes; dashboard will show the new numbers on its next stats poll.
- **Reuse (plugin – hook short-circuit):** When the hook short-circuits and shows a cached response, it calls `rag/feedback` and `rag/record-satisfied` → same backend counters. Again, dashboard picks this up when it next fetches stats.

So any “use cached response” path (dashboard or plugin) updates the **same** backend store. No separate token or reuse state is kept per client.

## Keeping the dashboard in sync with the plugin

The dashboard **polls** `GET /api/v1/prompts/rag/stats` every 30 seconds. So if you use the plugin to “Use this” or short-circuit, the dashboard’s RAG impact section will show the updated tokens saved and reuses within 30 seconds, or immediately after any dashboard action that triggers a refresh (e.g. “Use this” on the dashboard).

## Summary

- Use the **same gateway URL** in both (e.g. `http://localhost:8080`) and the **same org** (e.g. `default-org`).
- All reuse flows (dashboard “Use this”, plugin Live Similar “Use this”, hook short-circuit) call the same backend endpoints and update the same counters.
- Tokens saved and other RAG stats are **consistent** between dashboard and plugin because there is a single source of truth (the backend).
