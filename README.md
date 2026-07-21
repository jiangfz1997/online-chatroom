# Online Distributed Chatroom System

A scalable, real-time chatroom system built on **Spring Boot**, **WebSocket**, **Kafka**, **Redis**, and **DynamoDB**, deployed as three independently scalable microservices on **K3s**. The frontend is a **Vue 3 + TypeScript** single-page app served from S3.

> The original implementation was written in Go — it's archived at [`archive/go/`](archive/go/) (tag `v1.0-go`). Java/Spring Boot is now the primary stack.

---

## Architecture

```
                          ┌─────────────┐
                          │   Browser    │
                          │  (Vue 3 SPA) │
                          └──────┬───────┘
                                 │  HTTPS / WSS
                                 ▼
                       ┌───────────────────┐
                       │  Ingress (Traefik) │
                       │   /api      /ws    │
                       └─────────┬──────────┘
                    ┌────────────┴────────────┐
                    ▼                         ▼
           ┌─────────────────┐       ┌──────────────────┐
           │   api-server     │       │    ws-server      │
           │ (Spring Boot)    │       │ (Spring Boot)     │
           │ REST: auth,      │       │ WebSocket hub,    │
           │ chatroom mgmt    │       │ routed broadcast   │
           └────────┬─────────┘       └─────┬────────┬────┘
                    │                        │        │
                    │                        ▼        ▼
                    │                 ┌────────────┐ ┌─────────┐
                    │                 │   Redis    │ │  Kafka  │
                    │                 │ (cache,    │ │ (shared │
                    │                 │ routing    │ │ consumer│
                    │                 │ table,     │ │ group,  │
                    │                 │ pub/sub    │ │ 3       │
                    │                 │ delivery)  │ │ partns) │
                    │                 └────────────┘ └────┬────┘
                    │                                    │
                    ▼                                    ▼
              ┌───────────┐                    ┌───────────────────┐
              │ DynamoDB   │◄───────────────────│  persist-worker    │
              │ (users,    │   batched writes   │ (drains Redis      │
              │ chatrooms, │                    │  to_persist queue  │
              │ messages)  │                    │  → DynamoDB)       │
              └───────────┘                    └────────────────────┘
```

**Message flow:** a client sends a chat message over its WebSocket connection to any `ws-server` pod → the pod broadcasts it to its own locally-connected clients immediately, caches it in Redis for instant recent-history reads, and publishes it to Kafka. All `ws-server` pods share a single Kafka consumer group (3 partitions), so Kafka's normal partition assignment spreads message-processing load across pods instead of fanning every message out to every pod. Whichever pod ends up owning the partition for a message looks up a Redis routing table (`room:{roomId}:instances`, kept in sync by each pod as clients join/leave rooms) and forwards the message via Redis Pub/Sub only to the pod(s) that actually have a client in that room. `persist-worker` is decoupled from Kafka entirely — it polls a Redis queue (`room:{roomId}:to_persist`) that `ws-server` writes to on every message, and batch-writes to DynamoDB.

This separates concerns cleanly: `ws-server` never talks to DynamoDB on the hot path, so connection-heavy load doesn't compete with persistence I/O, and `ws-server` can scale horizontally (via HPA) independently of the write-heavy `persist-worker`. It also means cross-pod delivery is precisely targeted rather than broadcast — a pod with no clients in a given room never has to process (or even receive) messages for it.

---

## Services

| Service | Stack | Responsibility |
|---|---|---|
| [`api_server_java/`](api_server_java/) | Spring Boot | User registration/login (JWT), chatroom CRUD, join/exit, message history REST endpoint |
| [`ws_server_java/`](ws_server_java/) | Spring Boot + Spring WebSocket | Authenticated WebSocket connections, in-memory session hub, Redis-routed cross-pod delivery via a shared Kafka consumer group, Redis-backed recent-history cache |
| [`persist_worker_java/`](persist_worker_java/) | Spring Boot | Polls a Redis queue (written by `ws-server`) and persists messages to DynamoDB — has no Kafka dependency |
| [`frontend/vue-chat/`](frontend/vue-chat/) | Vue 3 + TypeScript + Vite | Login/register, chatroom list, chat window UI |

### api-server REST API (`/api`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/register` | Create a user account |
| POST | `/api/login` | Authenticate and receive a JWT |
| GET | `/api/health` | Health check |
| POST | `/api/chatrooms` | Create a chatroom |
| GET | `/api/chatrooms` | List chatrooms |
| GET | `/api/chatrooms/{roomId}` | Get chatroom details |
| GET | `/api/chatrooms/user/{username}` | List chatrooms a user belongs to |
| POST | `/api/chatrooms/join` | Join a chatroom |
| POST | `/api/chatrooms/exit` | Leave a chatroom |
| GET | `/api/chatrooms/{roomId}/messages` | Fetch message history |
| GET | `/api/chatrooms/{roomId}/enter` | Enter a chatroom session |

Auth is JWT-based (`JwtAuthFilter` / `JwtUtil`), with the same secret shared with `ws-server` so WebSocket upgrades can be authenticated via `WsAuthInterceptor`.

### ws-server

Exposes a WebSocket endpoint (path `/ws`, proxied by ingress) that clients connect to after authenticating. `Hub` tracks live `ClientSession`s per pod and, on every room-membership change, updates a Redis routing table (`RedisRoutingService`) recording which pods currently have local clients in a room. All pods share one Kafka consumer group (`ChatMessageProducer`/`ChatMessageConsumer`, topic `chat_messages`, 3 partitions), so a message sent to one pod is processed by whichever pod owns that message's partition, which then looks up the routing table and forwards it via Redis Pub/Sub (`RedisPubSubConfig`/`InstanceMessageListener`) only to the pod(s) that actually need it — not a blind broadcast to every pod. An `InstanceHeartbeatService` refreshes a per-pod liveness key every 10s so routing entries left behind by a crashed pod are pruned lazily instead of leaking.

---

## Infrastructure & Deployment

| Path | Purpose |
|---|---|
| [`docker/`](docker/) | Dockerfiles for containerized builds |
| [`docker-compose.yml`](docker-compose.yml) | Local dependency stack: Kafka (KRaft mode, single broker), Redis, DynamoDB Local. Java services run directly via `mvn spring-boot:run` against this stack. |
| [`k3s-cloud/`](k3s-cloud/) | Production K3s manifests: `api-server.yaml`, `ws-server.yaml`, `persist-worker.yaml`, `kafka.yaml`, `redis.yaml`, `ingress.yaml` |
| [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) | CI/CD: builds & pushes each service image to ECR, rolls out the k3s deployments over SSH, and builds/deploys the frontend to S3 |

### CI/CD pipeline

On every push to `master`:
1. **Build & push** — `api-server`, `ws-server`, and `persist-worker` images are built and pushed to ECR in parallel (matrix build), authenticated via AWS OIDC (no long-lived credentials).
2. **Deploy backend** — SSHes into the k3s host and runs `kubectl rollout restart` for all three deployments.
3. **Deploy frontend** — builds the Vue app (`npm run build`) with the production API/WS URLs injected as build-time env vars, then syncs the `dist/` output to an S3 bucket.

### Cloud topology (K3s)

- `ws-server` runs behind an HPA (1–3 replicas, scales on memory utilization) since it holds long-lived WebSocket connections.
- `api-server` and `persist-worker` run as single replicas — they're stateless/idempotent and not on the connection-count hot path.
- `kafka` and `redis` run in-cluster as a single-broker StatefulSet / Deployment respectively (not HA — this is a cost-optimized personal deployment, not a production-grade cluster).
- The `chat_messages` topic must have 3 partitions (matches `ws-server`'s HPA `maxReplicas`) for the shared consumer group to spread load across pods instead of pinning all processing to one pod. Spring only creates the topic with the right partition count on first creation — an already-existing topic needs a one-time manual `kafka-topics.sh --alter --topic chat_messages --partitions 3`.
- Traefik ingress splits traffic by path prefix: `/api` → `api-server:8080`, `/ws` → `ws-server:8081`, with WebSocket upgrade headers and long proxy timeouts configured for persistent connections.
- DynamoDB is the real AWS service in the cloud deployment (`AWS_DYNAMODB_ENDPOINT` empty); locally it points at `dynamodb-local`.

---

## Running Locally

### Prerequisites

- Docker + Docker Compose
- JDK 21 and Maven
- Node.js 20+ (for the frontend)

### 1. Start dependencies

```bash
docker-compose up --build
```

This brings up Kafka (port `9094` for host access), Redis (`6379`), and DynamoDB Local (`8000`).

### 2. Run the backend services

Each Java service is run directly with Maven, pointing at the Compose stack:

```bash
cd api_server_java && mvn spring-boot:run       # http://localhost:8080
cd ws_server_java && mvn spring-boot:run        # http://localhost:8081
cd persist_worker_java && mvn spring-boot:run   # background Kafka consumer
```

### 3. Run the frontend

```bash
cd frontend/vue-chat
npm install
npm run dev
```

Set `VITE_API_BASE_URL` and `VITE_WS_BASE_URL` (e.g. in `frontend/vue-chat/.env.local`) to point at the local `api-server`/`ws-server` instances.

---

## Repository Layout

```
api_server_java/      Spring Boot REST API (auth, chatrooms)
ws_server_java/        Spring Boot WebSocket hub (real-time messaging)
persist_worker_java/   Spring Boot Kafka consumer (Redis/Kafka -> DynamoDB)
frontend/vue-chat/     Vue 3 + TypeScript SPA
docker/                Dockerfiles per service
k3s-cloud/              K8s manifests for the production cluster
.github/workflows/     CI/CD pipeline (build, push, deploy)
archive/go/             Retired Go implementation (kept for reference)
```
