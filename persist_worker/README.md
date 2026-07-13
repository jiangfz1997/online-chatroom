## Persist Worker

The Persist Worker is a background service responsible for **persisting recent chat messages** stored in Redis into DynamoDB. This design decouples real-time message handling from database operations, improving system performance and reliability.

### Responsibilities

- Periodically scan Redis for recent chatroom messages
- Parse and batch format message data
- Write messages to DynamoDB for long-term storage
- Optionally delete or mark processed messages in Redis

## Persist Worker Directory Structure

| Folder / File     | Description |
|-------------------|-------------|
| `dynamodb/`       | Handles batch write operations to DynamoDB. |
| `logger/`         | Logging utility used for debug and persistence tracking. |
| `logs/`           | Runtime logs directory. |
| `persist/`        | Core logic for scanning Redis, formatting messages, and invoking DB writes. |
| `.env`            | Environment configuration (Redis address, DynamoDB endpoint, etc.). |
| `Dockerfile`      | Docker image definition for the worker service. |
| `go.mod`, `go.sum`| Go module dependencies. |
| `main.go`         | Entry point that initializes dependencies and starts the worker loop. |

---

## Docker Build & Run (Persist Worker)

This service is designed to run as a background worker that periodically persists messages from Redis to DynamoDB.

### Build the Docker image

```bash
docker build -t persist-worker .
docker run --env-file .env persist-worker
