# Embedding Service

HTTP API for text embeddings using **sentence-transformers** (model: `all-MiniLM-L6-v2`, 384 dimensions). Used by the prompt-service for real similarity computation.

## Run locally

```bash
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8081
```

First request may be slow while the model downloads. Health: `GET http://localhost:8081/health`.

## API

- **POST /embed**  
  Body: `{"text": "Your prompt or sentence here"}`  
  Response: `{"embedding": [0.1, -0.02, ...], "model": "...", "dim": 384}`

- **GET /health**  
  Returns `{"status": "ok", "model": "..."}`.

## Docker

```bash
docker build -t embedding-service ./embedding-service
docker run -p 8081:8081 embedding-service
```

## Config (prompt-service)

Set `embedding.service.url=http://localhost:8081` (or `http://embedding-service:8081` in Docker) so the prompt-service uses real embeddings. Leave empty to use stub embeddings.
