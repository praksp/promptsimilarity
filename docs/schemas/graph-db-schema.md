# Graph Database Schema (Neo4j)

## Node labels

- **User** — `(id: string, org_id: string)`
- **Prompt** — `(id: string, org_id: string, created_at: long)`
- **Chat** — `(id: string, room_id: string, created_at: long)`
- **Room** — `(id: string, external_id: string, room_type: string, org_id: string)`
- **Topic** — `(key: string, org_id: string)` (optional aggregate)

## Relationships

- `(User)-[:AUTHORED]->(Prompt)`
- `(Prompt)-[:SIMILAR_TO {score: float}]->(Prompt)`
- `(User)-[:PARTICIPATES_IN]->(Chat)`
- `(Chat)-[:PART_OF]->(Room)`
- `(Chat)-[:REFERENCES_PROMPT]->(Prompt)`
- `(Prompt)-[:BELONGS_TO_TOPIC]->(Topic)` (optional)

## Cypher examples

**Record prompt:**  
`MERGE (u:User {id: $userId}) MERGE (p:Prompt {id: $promptId, org_id: $orgId, created_at: $createdAt}) MERGE (u)-[:AUTHORED]->(p)`

**Record similarity:**  
`MATCH (a:Prompt {id: $id1}), (b:Prompt {id: $id2}) MERGE (a)-[:SIMILAR_TO {score: $score}]->(b)`

**Knowledge graph (topics + users):**  
`MATCH (p:Prompt)-[:BELONGS_TO_TOPIC]->(t:Topic), (u:User)-[:AUTHORED]->(p) WHERE p.org_id = $orgId RETURN t, count(DISTINCT u) as user_count, collect(DISTINCT p.id) as prompt_ids`
