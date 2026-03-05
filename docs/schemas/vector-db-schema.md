# Vector Database Schema (Qdrant)

## Collection: `prompts`

| Field       | Type     | Description                    |
|------------|----------|--------------------------------|
| id         | UUID     | Same as prompt_id from service |
| vector     | float[]  | Embedding (384 or 768 dims)    |
| payload    | map      | See below                      |

**Payload:**
- `prompt_id` (keyword)
- `user_id` (keyword)
- `org_id` (keyword)
- `text_hash` (keyword) — optional, for dedup
- `created_at` (integer, Unix ms)
- `language` (keyword)

**Index:** HNSW for approximate nearest neighbor; size = 768 (or 384).

---

## Collection: `reasoning`

| Field   | Type    | Description        |
|--------|---------|--------------------|
| id     | UUID    | reasoning_id       |
| vector | float[] | Reasoning embedding|
| payload| map     | See below          |

**Payload:**
- `reasoning_id` (keyword)
- `prompt_id` (keyword)
- `user_id` (keyword)
- `org_id` (keyword)
- `created_at` (integer)

---

## Scaling

- Use Qdrant distributed mode or Qdrant Cloud for horizontal scaling.
- Shard by `org_id` for tenant isolation and efficient filtering.
