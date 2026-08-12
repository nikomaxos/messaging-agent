# ENTERPRISE A2P SMS & CPAAS PLATFORM - ARCHITECTURE KNOWLEDGE
**Version**: 2.0 (Microservices & Kubernetes Era)
**Status**: ACTIVE SOURCE OF TRUTH

## ⚠️ AI CONTEXT BOOTSTRAP & INITIALIZATION
**ATTENTION ALL AI AGENTS AND HUMAN DEVELOPERS:** 
Before reading, modifying, or writing *any* code in this repository, you MUST strictly adhere to the architectural boundaries, directory structures, and styling rules defined in this document. We have migrated from a Modular Monolith to a **Kubernetes (K3s) Event-Driven Microservices Architecture** using a Big Bang approach. 
1. Do NOT add new monolithic packages to old Spring Boot directories.
2. ALL inter-service communication must happen asynchronously via Kafka unless explicitly designed as a synchronous API Gateway call.
3. Microservices must be strictly isolated (Logical Database-per-Service).
4. Code must follow the strict formatting and logging standards defined below.
Violating these rules compromises the massive scale and fault-tolerance of the platform.

---

## 1. SYSTEM ARCHITECTURE & DATA FLOW

The platform is designed to process hundreds of thousands of asynchronous messages with zero-latency OTP prioritization, fractional rate limiting, and infinite horizontal scalability. It follows a strict **Event-Driven Microservices Pattern**.

### Logical Database-per-Service (The Golden Mean)
We utilize a single, physical High-Availability (HA) PostgreSQL Cluster to minimize infrastructure overhead, but we enforce strict **Logical Database-per-Service** separation. 
- Example: `CREATE DATABASE db_core;`, `CREATE DATABASE db_billing;`.
- Each microservice has its own dedicated credentials and can *only* see its own logical database. 
- There are NO cross-database JOINs. Data sharing occurs via Kafka Event sourcing.

### Event-Driven Caching (Push Model) & Redis Source of Truth
The **Routing Engine** must *never* execute synchronous SQL queries against PostgreSQL. To avoid the "Thundering Herd" problem and prevent database collapse under 10k+ TPS load, we utilize a strict Redis Push Model:
1. **Pre-loading (Warm-up)**: Upon startup, the Routing Engine loads active routing rules into Redis.
2. **O(1) Lookups**: The Routing Engine uses Redis Hashes (e.g., `HGETALL route:mccmnc:20201`) to make instantaneous routing decisions below 1ms.
3. **Event-Driven Updates**: When an administrator updates a route via the UI, the `Core Service` saves to Postgres and publishes a `config.routing.updated` event to Kafka. The Routing Engine consumes this event and updates Redis.
4. **Circuit Breaker (Fail-Fast)**: If Redis becomes unreachable, the Routing Engine will *pause* Kafka consumption immediately. It will NEVER fallback to PostgreSQL. The SMS remains safely in Kafka until Redis recovers.

### Core Message Data Flow
1. **Ingress (SMPP Edge Node)**: Customer connects via TCP. The node receives the `submit_sm` packet, instantly drops it into the Kafka `inbound.raw` topic, and returns a fast ACK to the customer.
2. **Routing & Dispatch Engine**: Consumes from `inbound.raw`. Checks the Redis cache for routing rules (O(1) lookup). Applies Fractional Rate Limiting (Redis `PX` keys) and OTP Prioritization (Redis `ZADD` composite scoring). Drops the enriched message into a destination Kafka topic (e.g., `outbound.smpp`, `outbound.rcs`).
3. **Egress (Workers)**: Isolated workers (e.g., SMPP Egress, Mautrix RCS) consume their specific `outbound.*` topic and dispatch the message to the upstream supplier. 

---

## 2. DIRECTORY STRUCTURE (The Tree)

The repository is organized into distinct, isolated microservices and shared deployment configurations.

```text
/home/nick/Development/messaging-agent/
├── /services/
│   ├── /api-gateway/          # (Node.js/Express) Central entry point, JWT auth, rate limits
│   ├── /core-service/         # (Java/Spring Boot) UI CRUD, Admin APIs, Config publisher
│   ├── /billing-service/      # (Java/Spring Boot) Ledger, credit deduction, financial rules
│   ├── /smpp-edge-node/       # (Node.js or Java) TCP ingress only. Dumps to Kafka.
│   ├── /routing-engine/       # (Java/Spring Boot) Complex routing, Redis lookups, Kafka dispatch
│   └── /egress-workers/       
│       ├── /smpp-supplier/    # (Java) Consumes outbound.smpp, sends to upstream SMSC
│       ├── /rcs-mautrix/      # (Node.js) Consumes outbound.rcs, interacts with Mautrix API
│       └── /whatsapp/         # (Future)
├── /shared-libs/
│   ├── /messaging-core-java/  # Shared DTOs, Kafka SerDes, Enums for Java services
│   └── /messaging-core-js/    # Shared TS interfaces, utilities for Node.js services
├── /k8s-manifests/
│   ├── /base/                 # Standard Deployments, Services, ConfigMaps
│   └── /overlays/             # Environment specific (staging, production)
├── /admin-panel/              # (React/Vite) Frontend UI for the platform
└── /android-app/              # Legacy / Companion Device WebSockets Client
```

*Agent Instruction*: When creating a new microservice, create a new folder under `/services/`. When modifying Kubernetes deployment logic, operate exclusively within `/k8s-manifests/`.

---

## 3. CODE STYLE & CONVENTIONS

### Languages & Frameworks
- **Heavy Processing / Core / Routing**: Java 21+ with Spring Boot 3.x (Excellent concurrency with Virtual Threads for SMPP/Kafka).
- **API Gateways / Lightweight API integrations**: Node.js (TypeScript) for rapid I/O.
- **Frontend**: React (TypeScript) with Vite and TailwindCSS.

### Naming Conventions
- **Variables & Functions**: `camelCase` (e.g., `routeMessage`, `inboundQueue`).
- **Classes & Interfaces**: `PascalCase` (e.g., `RateLimiterService`, `SmppSessionManager`).
- **Directories & Endpoints**: `kebab-case` (e.g., `/api/v1/routing-rules`, `/services/billing-engine`).
- **Database Tables**: `snake_case` (e.g., `smpp_client`, `routing_rule`).
- **Redis Keys**: `domain:entity:id` (e.g., `route:mccmnc:20201`, `rate:limit:customer1:GR`).

### Error Handling & Logging
- **Structured JSON Logging**: All microservices must output logs in raw JSON format to stdout. This ensures seamless parsing by Kubernetes log aggregators (e.g., Promtail/Loki or FluentBit).
- **Format Requirements**: Logs must include `timestamp`, `level`, `service`, `trace_id` (if applicable), and `message`.
- **Exception Handling**: Catch exceptions at the API/Kafka consumption boundary. Do not allow stack traces to leak to HTTP responses; return standardized JSON error objects `{ "error": "code", "message": "friendly message" }`.

---

## 4. INFRASTRUCTURE & DEPLOYMENT

### Kubernetes (K3s) on Proxmox
The platform operates on a **K3s (Kubernetes)** cluster deployed across a pool of Proxmox VMs. 
- **Control Plane**: Manages the cluster API and scheduling.
- **Worker Nodes**: Execute the microservice pods.
- **Stateful Sets**: Redis is deployed in **Sentinel mode (1 Master, 2 Replicas)** inside the cluster for automatic failover. Kafka and Postgres operate as HA clusters.

### Event-Driven Autoscaling (KEDA)
Microservice elasticity is governed by **KEDA** (Kubernetes Event-driven Autoscaling).
- Instead of scaling based purely on CPU/RAM, KEDA scales Egress Workers and Routing Engines based on **Kafka Topic Lag**.
- E.g., If `outbound.rcs` lag exceeds 5,000 messages, KEDA automatically scales the RCS Egress Worker pods from 1 to 20 instances. Once the lag reaches 0, it scales them back down.
- SMPP Edge Nodes scale based on concurrent TCP connections or CPU. 
