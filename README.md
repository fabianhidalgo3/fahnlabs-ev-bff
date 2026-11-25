# ev-bff · Backend for Frontend and AI assistant

Part of **EV yard power control** by FAHNLABS: real-time power allocation for an electric-vehicle charging yard.
Overview, architecture and deployment: [fahnlabs-ev-deploy](https://github.com/fabianhidalgo3/fahnlabs-ev-deploy).

The BFF is the only API the dashboard ([ev-web](https://github.com/fabianhidalgo3/fahnlabs-ev-web)) talks to. It
validates requests, relays the engine's live state, builds CSV exports and runs the AI operations assistant. It never
accesses a database: all data comes from [ev-engine](https://github.com/fabianhidalgo3/fahnlabs-ev-engine).

## Stack

Java 25 · Spring Boot 4.2 · springdoc-openapi (OpenAPI 3.1) · Anthropic Java SDK.

## Run

```bash
./mvnw spring-boot:run            # :9000
# or
./mvnw -q package -DskipTests && java -jar target/bff-2.0.0.jar
```

The jar also serves the built front from `FRONT_DIR`.

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `9000` | HTTP port |
| `MOTOR_URL` / `MOTOR_TOKEN` | `http://localhost:9200` / empty | Engine API |
| `OCPP_PUBLIC_URL` / `OCPP_PUBLIC_PORT` | empty / `9100` | Public OCPP address shown in the UI |
| `ANTHROPIC_API_KEY` / `AGENT_MODEL` | empty / `claude-opus-5-5` | Assistant with Claude |
| `GEMINI_API_KEY` / `GEMINI_MODEL` | empty | Fallback assistant when there is no Anthropic key |
| `FRONT_DIR` | `front/dist/front/browser` | Built front to serve |

Put secrets in a git-ignored `.env`, never in the repository.

## API

REST under `/api/v1` with validation and RFC 9457 problem details: state and configuration, vehicles, chargers,
OCPP commands, charging plans, sessions, logs, energy, maintenance, CSV exports and `/assistant/messages`.
Live updates at `ws://…/ws/live`. Swagger UI: `/swagger-ui.html`.
Reference: [docs/api.md](https://github.com/fabianhidalgo3/fahnlabs-ev-deploy/blob/main/docs/api.md).

## AI assistant

Manual tool loop over the Messages API with read-only tools that call the engine (yard state, sessions, restricted
SQL on PostgreSQL and aggregations on MongoDB). It answers in English or Spanish and can navigate the dashboard.

## Code

| Path (`src/main/java/com/emob/bff`) | Content |
|---|---|
| `api/` | REST controllers |
| `motor/` | Engine client (`MotorClient`) and properties |
| `live/` | Live state relay over WebSocket |
| `assistant/` | Assistant tools, prompts and model clients |

## Author

© 2026 Fabián Hidalgo · FAHNLABS. All rights reserved.
