# Prompt Similarity & Collaboration Platform — Architecture

## Overview

Distributed system for ingesting prompts, computing similarity (embeddings + vector store), storing reasoning in a vector store, and connecting users working on similar topics via notifications and Teams/Slack. Designed for high throughput and low latency.

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                     DASHBOARD (React/Vite) + CURSOR PLUGIN (NPM)                 │
│  - Submit prompts, view similarity, find similar (search)                         │
│  - Calls API Gateway via REST (HTTP/JSON)                                        │
└─────────────────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         API GATEWAY (REST only, port 8080)                       │
│  - Single entry; forwards to Prompt Service via HTTP (RestClient)                │
│  - CORS, health at /q/health                                                     │
└─────────────────────────────────────────────────────────────────────────────────┘
                                        │ HTTP (JSON)
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    PROMPT SERVICE (REST internal, port 8080)                    │
│  - Ingest: embed → store (vector, search, feature, graph) → similarity check    │
│  - Find similar: embed → vector search                                           │
│  - Downstream failures are best-effort (recover with empty/skip); ingest still 200│
└─────────────────────────────────────────────────────────────────────────────────┘
          │                │                │                │                │
          │ gRPC           │ gRPC           │ gRPC           │ gRPC           │ gRPC
          ▼                ▼                ▼                ▼                ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│   VECTOR     │  │   SEARCH     │  │   FEATURE    │  │ NOTIFICATION │  │  COLLAB      │
│   SERVICE    │  │   SERVICE    │  │   STORE      │  │   SERVICE    │  │  + GRAPH     │
│  (in-memory  │  │ (Elastic)    │  │   (Redis)    │  │ (alerts log) │  │  SERVICE     │
│   dev)       │  │              │  │              │  │              │  │              │
└──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘
          │
          │  Embeddings via HTTP
          ▼
┌──────────────┐
│  EMBEDDING   │  Python FastAPI; sentence-transformers (all-MiniLM-L6-v2)
│  SERVICE     │  POST /embed { "text": "..." } → { "embedding": [...] }
└──────────────┘
```

## Components

### 1. API Gateway
- **Role**: Single REST entry for dashboard and plugin; forwards to Prompt Service over HTTP.
- **Tech**: Quarkus (REST Reactive, RestClient). No gRPC; health at `/q/health`.
- **Endpoints**: `POST /api/v1/prompts/ingest`, `GET /api/v1/prompts/similar`, `GET /api/v1/prompts/{id}`.
- **Resilience**: If Prompt Service returns 500 for `findSimilar`, gateway returns 200 with `[]`.

### 2. Prompt Service
- **Role**: Ingest prompts (no size limit), call embedding service (HTTP), then vector/search/feature/graph (gRPC); run similarity check; optionally notify.
- **Flow**: Receive prompt → embed (HTTP to embedding-service) → store in vector + search + feature store + graph (best-effort) → search similar in vector (exclude self) → if threshold met, notify + collaboration.
- **Internal API**: REST at `/internal/prompts` (ingest, similar, get) for the gateway. Downstream calls use gRPC (vector, search, notification, collaboration, graph, feature-store).
- **Resilience**: Vector/search/graph/notification/collaboration failures do not fail ingest or findSimilar; empty list or skip is returned so the user always gets 200.

### 3. Embedding Service (Python)
- **Role**: Produce text embeddings for similarity.
- **Tech**: FastAPI, sentence-transformers (`all-MiniLM-L6-v2`, 384 dims).
- **API**: `POST /embed` with `{"text": "..."}`; returns `{"embedding": [...], "model", "dim"}`. Health: `GET /health`.
- **Config**: Prompt service calls it via `embedding.service.url` (e.g. `http://embedding-service:8081` in Docker). Client uses HTTP/1.1 and JSON; empty or invalid text returns 400.

### 4. Vector Service
- **Role**: Store prompt embeddings and run similarity search (cosine).
- **Backend**: In-memory map for dev; production: Qdrant or Weaviate.
- **gRPC**: Store (prompt_id, user_id, org_id, embedding, metadata including `text_preview`); SearchSimilar(embedding, orgId, excludeUserId, topK, minScore).
- **Similarity**: Threshold for “similar” in ingest is configurable (default 0.65). Same org; excludes current user.

### 5. Search Service (Elasticsearch)
- **Role**: Keyword/hybrid search; index prompt text.
- **gRPC**: IndexPrompt. Best-effort from Prompt Service (failure does not fail ingest).

### 6. Feature Store Service
- **Role**: Distributed feature store (Redis).
- **gRPC**: Write prompt features. Best-effort from Prompt Service.

### 7. Notification Service
- **Role**: Receive similarity alerts (e.g. when two users have similar prompts).
- **Behavior**: Logs the event; can be wired to webhooks, Teams, Slack, etc.
- **gRPC**: SendSimilarityAlert. Best-effort from Prompt Service.

### 8. Collaboration Service
- **Role**: Create rooms (Teams/Slack) and add participants when similarity is detected.
- **gRPC**: CreateRoomFromSimilarity. Best-effort from Prompt Service.

### 9. Graph Service
- **Role**: Model prompts, users, chats, rooms; correlate chats to prompts (Neo4j-ready).
- **gRPC**: RecordPrompt. Best-effort from Prompt Service.

## Data Flow

1. **Prompt ingest**: Dashboard/Plugin → Gateway (REST) → Prompt Service (REST) → embed (HTTP) → store in vector + search + feature + graph (gRPC, best-effort) → similarity search in vector (exclude self) → if score ≥ threshold, notify + collaboration (best-effort) → return 200 with `similarUsers` (and `textPreview` per user).
2. **Find similar**: Dashboard → Gateway → Prompt Service → embed → vector search → return 200 with list (or `[]` on any failure).
3. **Notifications**: When similarity is detected, Prompt Service calls Notification Service (and Collaboration); currently logged; can be extended to push to clients or external systems.

## Technology Stack

| Layer            | Choice                                                                 |
|------------------|-----------------------------------------------------------------------|
| Backend          | Java 17+, Quarkus                                                     |
| Gateway ↔ Prompt | REST (HTTP/JSON), Quarkus RestClient Reactive                        |
| Prompt ↔ others  | gRPC (vector, search, notification, collaboration, graph, feature-store) |
| Embeddings       | Python FastAPI, sentence-transformers                                 |
| Vector DB        | In-memory (dev); Qdrant/Weaviate (production)                         |
| Keyword Search   | Elasticsearch (dev in-memory or external)                             |
| Feature Store    | Redis                                                                 |
| Graph DB         | Neo4j (optional)                                                       |
| Dashboard        | React, Vite, TypeScript; REST to gateway                               |
| Cursor plugin    | NPM/VS Code extension (TypeScript)                                    |

## Deployment (Docker)

- **Containers**: Each microservice in its own image. Gateway and Prompt Service use Quarkus `fast-jar`.
- **Start order**: No strict `depends_on: service_healthy` from Prompt Service to backends (avoids stuck “Waiting”); backends can start in parallel. Gateway waits for Prompt Service health when configured.
- **Build**: After Maven build, run `docker compose build` (e.g. via `clean-build-run.sh`) so images contain the latest jars.
- **Healthchecks**: Vector, search, graph, notification, collaboration, feature-store use TCP checks (`nc -z localhost <gRPC port`). Prompt Service and Gateway use `curl /q/health`.

## Similarity and Dashboard

- **Seeing similar prompts**: Use different user IDs (e.g. alice, bob), enter same or similar text, submit. Vector service must be running so prompts are stored and search returns results.
- **Threshold**: Default 0.65 (65% cosine similarity). Configurable in `PromptIngestionOrchestrator.SIMILARITY_THRESHOLD`.
- **Response**: Ingest returns `similarUsers[]` with `userId`, `promptId`, `similarityScore`, `textPreview`. Dashboard shows these in “Similarity with other users” and in Find similar (search) results.

## Security & Multi-Tenancy

- **Tenancy**: `org_id` and `user_id` on all operations; vector/search filter by org; exclude current user from similar-user results.
- **Auth**: Gateway can add JWT/API key validation; not required for local dev.

## Non-Functional Goals

- **Resilience**: Ingest and findSimilar return 200 with empty or partial data when downstream services fail (no 500 from gateway for findSimilar).
- **Latency**: Embedding and vector search are the main cost; timeouts and recovery keep the API responsive.
