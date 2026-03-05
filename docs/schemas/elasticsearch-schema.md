# Elasticsearch Indices

## Index: `prompts`

```json
{
  "mappings": {
    "properties": {
      "prompt_id": { "type": "keyword" },
      "user_id": { "type": "keyword" },
      "org_id": { "type": "keyword" },
      "text": { "type": "text", "analyzer": "standard" },
      "created_at": { "type": "date" },
      "language": { "type": "keyword" }
    }
  },
  "settings": {
    "number_of_shards": 2,
    "number_of_replicas": 1
  }
}
```

## Index: `reasoning`

```json
{
  "mappings": {
    "properties": {
      "reasoning_id": { "type": "keyword" },
      "prompt_id": { "type": "keyword" },
      "user_id": { "type": "keyword" },
      "org_id": { "type": "keyword" },
      "text": { "type": "text", "analyzer": "standard" },
      "created_at": { "type": "date" }
    }
  }
}
```

## Search behavior

- All user-facing search goes through Elasticsearch (keyword + full-text).
- Optional: hybrid search by fusing Elasticsearch scores with vector similarity (handled in Search Service).
