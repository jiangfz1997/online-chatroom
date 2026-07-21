# chatroom-apiserver

## API Server Structure

This service handles user authentication, chatroom management, and other RESTful API requests. Below is a brief description of each module:

| Folder / File             | Description |
|---------------------------|-------------|
| `dynamodb/`               | DynamoDB access logic, including query and write operations. |
| `handlers/`               | Request handlers for user and chatroom APIs. |
| `logger/`                 | Custom logging setup and helpers. |
| `logs/`                   | Output log directory (may be gitignored). |
| `middleware/`             | HTTP middlewares, such as token validation and CORS. |
| `models/`                 | Data models and request/response structures. |
| `redis/`                  | Redis client setup and session-related logic. |
| `router/`                 | API route groupings and initialization. |
| `test/`                   | Unit and integration tests. |
| `utils/`                  | Utility functions (e.g., UUID generation, timestamps). |
| `.env`                    | Environment variables for local development. |
---

## Docker Build & Run (Standalone)

To build and run the API server independently:

```bash
# Build the Docker image
docker build -t api-server .

# Run the container (example port and env)
docker run -p 8080:8080 --env-file .env api-server
```

