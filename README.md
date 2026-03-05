# Prompt Similarity & Collaboration Platform

Distributed system for ingesting prompts, computing similarity (embeddings + vector store), storing reasoning, and connecting users working on similar topics via notifications and Teams/Slack rooms.

## Features

- **Prompt input**: Text, no size limit (English to start).
- **Embeddings**: Python embedding service (sentence-transformers); prompt-service calls it via HTTP.
- **Vector database**: Stores embeddings; in-memory for dev (Qdrant-ready for production).
- **Elasticsearch**: Keyword search via Search Service (gRPC).
- **Feature store**: Redis-backed (Feature Store Service).
- **Java backend**: Quarkus microservices; **Gateway → Prompt Service via REST**; Prompt Service → Vector/Search/Notification/Collaboration/Graph/Feature-store via **gRPC**.
- **Multi-user**: Share prompt context; similarity shown in dashboard (user, prompt text preview, score). Notifications logged when similarity is detected.
- **Cursor plugin**: NPM/VS Code extension to send prompts to the local gateway.
- **Teams/Slack**: Collaboration Service (create rooms from similar users); Graph Service for prompt/chat correlation (Neo4j-ready).
- **Dashboard**: React (Vite) — submit prompts, view “Similarity with other users”, and “Find similar prompts” (search by text).

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Summary:

- **API Gateway** (REST only, port 8080) forwards to **Prompt Service** (REST at `/internal/prompts`). No gRPC on the gateway.
- **Prompt Service** uses **HTTP** to the **Embedding Service** (Python) and **gRPC** to Vector, Search, Feature Store, Notification, Collaboration, and Graph services.
- **Vector Service**: stores embeddings and runs similarity search (cosine). Must be running to see similar prompts.
- **Resilience**: If vector/embedding/search etc. are down, ingest and “Find similar” still return 200 (empty or partial data instead of 500).

## Prerequisites

- **Docker** (recommended for full e2e; scripts can start Docker Desktop on macOS)
- Java 17+ and Maven (for building; can use Docker for build)
- Node 18+ (for dashboard and Cursor plugin)

## Setup

### 1. Build (Java + dashboard)

```bash
# From repo root
mvn clean package -DskipTests -Dquarkus.package.type=fast-jar
cd dashboard && npm ci && npm run build && cd ..
```

### 2. Run with Docker (recommended)

Scripts ensure Docker is running, then build images and start the stack. **After a code change, rebuild images** so containers use the new jars:

```bash
# Full clean + build + docker build + up
./scripts/clean-build-run.sh
```

- **clean-build-run.sh**: Ensures Docker is up → cleans `target/` and `dashboard/dist` → Maven build (all modules) → dashboard build → **docker compose build --no-cache** → docker compose up -d.
- **start.sh** / **restart.sh**: See `scripts/` for other options.

**Ports**: Gateway `8080`, Prompt Service (internal) `8082`, Embedding `8081`, Vector `9002`, Search `9003`, etc.

**Config (Docker)**:
- Gateway: `QUARKUS_REST_CLIENT_PROMPT_SERVICE_URL=http://prompt-service:8080`
- Prompt Service: `EMBEDDING_SERVICE_URL` → `embedding.service.url` (e.g. `http://embedding-service:8081`); gRPC client hosts/ports for vector, search, etc. set via env.

### 3. Run dashboard (dev)

```bash
cd dashboard
npm install
npm run dev
```

Open `http://localhost:5173` (or the port Vite prints). The app calls the API at the gateway (`http://localhost:8080` by default; set `VITE_API_BASE_URL` if needed).

### 4. Seeing similar prompts

1. Ensure **vector-service** is running: `docker compose ps`.
2. In the dashboard, pick **different user IDs** (e.g. **alice**, then **bob**).
3. Enter the **same or similar text** for each user and submit.
4. After the second submit, “Similarity with other users” shows the other user(s), prompt text preview, and score. **Find similar prompts** (search box) also returns matches by text.

Similarity threshold is 0.65 (65% cosine similarity); configurable in `PromptIngestionOrchestrator.SIMILARITY_THRESHOLD`.

### 5. Embedding service (real embeddings)

- In Docker Compose, **embedding-service** is included (Python, sentence-transformers). Prompt-service uses it when `embedding.service.url` is set (e.g. `http://embedding-service:8081`).
- If the embedding service is down or returns an error, prompt-service falls back to stub embeddings so ingest still succeeds.

### 6. Cursor plugin

```bash
cd cursor-plugin
npm install
npm run build
# Install in Cursor/VS Code (e.g. Install from VSIX if you run "npm run package").
```

Configure gateway URL (e.g. `http://localhost:8080`), `orgId`, `userId` as needed.

## API (REST)

- **POST /api/v1/prompts/ingest**  
  Body: `{ "userId", "orgId", "text", "language" }`.  
  Returns: `{ "promptId", "similarityDetected", "similarUsers": [{ "userId", "promptId", "similarityScore", "textPreview" }] }`.
- **GET /api/v1/prompts/list**  
  Returns: JSON array of all stored prompts: `[{ "promptId", "userId", "orgId", "text", "createdAt" }, ...]` (newest first). Use this to confirm prompts are being saved.

- **GET /api/v1/prompts/similar?text=...&orgId=...&userId=...&topK=10&minScore=0.7**  
  Returns: `[{ "promptId", "userId", "score", "textPreview" }]`. Returns `[]` on downstream failure (no 500).

- **GET /api/v1/prompts/{promptId}**  
  Returns one prompt by ID.

Health: **GET /q/health** (gateway and prompt-service).

## Testing

- **Unit tests**: `./scripts/run-e2e-tests.sh` (or `mvn test`) runs all Maven unit/integration tests. API Gateway tests include a WireMock-based test that proves the gateway uses the REST client (not gRPC) to call prompt-service.
- **Live E2E**: Run `./scripts/run-e2e-tests.sh --live` to validate the running stack: health, ingest (must return 200 with promptId), list prompts (must return 200 and a JSON array), and find-similar (must return 200). The script starts Docker Compose if the gateway is not up. Use this after a clean build to confirm prompts are saved and list works.

## Notifications

When similarity is detected (score ≥ threshold), the Prompt Service calls the **Notification Service** (gRPC). The default implementation **logs** the event. To see it: `docker compose logs -f notification-service`. In production you can wire this to email, Slack, or in-app alerts.

## Database schemas (logical)

- **Vector**: prompt_id, user_id, org_id, embedding, metadata (e.g. text_preview).
- **Elasticsearch**: prompt index (text, user, org, time).
- **Graph (Neo4j)**: Users, Prompts, Chats, Rooms and relationships.
- **Feature store (Redis)**: prompt/org features.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for details.

## License

Use as needed for your organization.
