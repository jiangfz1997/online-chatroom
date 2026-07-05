# Archived — Go Implementation

This directory contains the original Go implementation of the chatroom microservices,
superseded by the Java/Spring Boot rewrite in the project root.

**Last working state**: tag [`v1.0-go`](https://github.com/jiangfz1997/online-chatroom/releases/tag/v1.0-go)

## Services

| Directory | Description |
|-----------|-------------|
| `api_server/` | Go REST API server (Gin + DynamoDB + Redis) |
| `websocket_server/` | Go WebSocket server (Gorilla WS + Kafka + Redis) |
| `persist_worker/` | Go Kafka consumer → DynamoDB persistence worker |
| `kafka/` | Standalone Kafka docker-compose for local dev |
| `k3s-local/` | Kubernetes manifests for local k3s cluster |
| `k3s-online/` | Kubernetes manifests for production deployment |

## Running the Go version

Checkout tag `v1.0-go` to restore the original working state:

```bash
git checkout v1.0-go
docker compose up -d
```
