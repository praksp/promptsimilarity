# Multi-user simulation tests

Run the API gateway and prompt service, then execute this script to simulate multiple users ingesting prompts and triggering similarity notifications.

## Prerequisites

- Services running (e.g. `mvn quarkus:dev` in api-gateway and prompt-service, or docker-compose up)
- curl

## Run

```bash
./simulate-multi-user.sh
```

Or manually:

```bash
# User 1
curl -s -X POST http://localhost:8080/api/v1/prompts/ingest \
  -H "Content-Type: application/json" \
  -d '{"userId":"user1","orgId":"org1","text":"How do I implement JWT authentication in Spring Boot?","language":"en"}'

# User 2 – similar prompt
curl -s -X POST http://localhost:8080/api/v1/prompts/ingest \
  -H "Content-Type: application/json" \
  -d '{"userId":"user2","orgId":"org1","text":"I need to add JWT auth to my Spring Boot app","language":"en"}'
```

High similarity between these prompts should trigger a notification and optionally a room creation.
