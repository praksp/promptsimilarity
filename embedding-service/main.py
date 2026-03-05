"""
Embedding service: HTTP API for text embeddings using sentence-transformers.
Model: all-MiniLM-L6-v2 (384 dimensions, fast, good for similarity).
"""
from contextlib import asynccontextmanager
from typing import List

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

# Lazy load model on first request
_model = None
MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"


def get_model():
    global _model
    if _model is None:
        from sentence_transformers import SentenceTransformer
        _model = SentenceTransformer(MODEL_NAME)
    return _model


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Preload model at startup so first request isn't slow
    get_model()
    yield
    # cleanup if needed
    pass


app = FastAPI(title="Prompt Similarity Embedding Service", lifespan=lifespan)


class EmbedRequest(BaseModel):
    text: str | None = None


class EmbedResponse(BaseModel):
    embedding: List[float]
    model: str = MODEL_NAME
    dim: int = 384


@app.post("/embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest):
    text = (request.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="text must be non-empty string")
    try:
        model = get_model()
        # sentence-transformers encode returns numpy array; convert to list
        emb = model.encode(request.text.strip(), convert_to_numpy=True)
        return EmbedResponse(
            embedding=emb.tolist(),
            model=MODEL_NAME,
            dim=len(emb),
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health():
    return {"status": "ok", "model": MODEL_NAME}
