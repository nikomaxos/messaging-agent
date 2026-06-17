# Messaging Agent - Learned Skills & Architecture Patterns

This document tracks learned skills, architecture patterns, and domain-specific knowledge acquired while developing the messaging agent system. It should be updated to retain long-term project context.

## 1. Mautrix-Google Messages Bridge Integration
*   **Authentication & Session Management**: The Mautrix bridge login mechanism requires an actual Google account session. Using `curl` with extracted cookies directly into the bridge's local Postgres DB is far more reliable than standard QR code pairing which frequently fails or disconnects (`SESSION_COOKIE_INVALID`).
*   **Synapse Auto-Registration**: To allow Android devices to natively communicate through the bridge, the devices act as virtual Matrix users. If the Synapse homeserver returns a `403 Forbidden` during portal room joining, it means the device user is not registered. We implemented a dummy auth fallback mechanism to auto-register new device users directly through the `MatrixRouteService`.
*   **Rate Limiting**: Google Messages applies strict rate limiting. A `RateLimiter` must be used (enforcing ~10 RPS / 100ms delay) when dispatching messages to avoid IP bans or silent drops.

## 2. Fallback Engine Architecture
*   **Event-Driven vs. Polling**: Long-polling the database for expired RCS dispatches incurs significant latency. We implemented a Hybrid Architecture:
    *   **Primary (Trigger-Based)**: When a message formally exits the internal queue and is pushed to Matrix/RCS (`DISPATCHED`), a dynamically scheduled asynchronous event (`TaskScheduler`) is armed to trigger exactly at `Instant.now() + rcsExpirationSeconds`.
    *   **Safety Net**: A slower 30-second database sweeper is retained purely to catch any delayed fallbacks in the event the in-memory timers are lost during a backend restart.
*   **Routing Loop Prevention**: When falling back from RCS to SMPP, you MUST clear `deviceGroup`, `device`, and `routingMode`. If these are not cleared when moving the message back to `QUEUED` status, the Matrix or WebSocket queue drainers will immediately scoop the message back up, creating an infinite dispatch-fail-fallback loop.

## 3. SMPP Back-Channel & DLR Syncing
*   **Session Lifecycle**: SMPP clients can randomly disconnect. The `SmscConnectionManager` requires active heartbeat/enquire_link monitoring and must forcefully close and unbind zombie connections before attempting a reconnect to avoid TCP socket leaks.
*   **Status Codes**: Native SMPP status codes must be used to communicate rich error states back to upstream clients:
    *   `ESME_RDELIVERYFAILURE (0x00000011)` + `TLV 0x1400 = 0x01`: No RCS Capability (Device cannot receive RCS).
    *   `ESME_RDELIVERYFAILURE (0x00000011)` + `TLV 0x1400 = 0x02`: No Android Device Online.
*   **Matrix DLRs**: Unlike standard SMS, Matrix messages do not inherently provide a "Sent" receipt when successfully handed off to the carrier. We implemented a hybrid `TRACK_DLR_ONLY` WebSocket command to have the native Android agent track the underlying `bugle_db` SQLite database to sniff actual carrier status updates (`2=SENT`, `1=DELIVERED`).

## 4. Diagnostic Workarounds
*   When executing commands in the backend container that rely on `.env` variables, it's safer to `docker exec -it ma-backend bash` and source the environment, or natively map `application.yml` correctly.
*   The `scratch_logs.py` script is an excellent way to grep and trace specific `correlationId` message lifecycles across the asynchronous rate-limiters, websockets, and expiration schedulers.

## 5. Linux Migration & Deployment Methodology
*   **State Preservation**: When migrating the environment, we must preserve state to prevent disruptive reconnections. The `migration_backup.zip` archives the Postgres database dump (`messagingagent_dump.sql`), the Matrix configuration state (`matrix/*`), and the Android ADB authorization keys (`adbkey*`).
*   **Automated Restore Flow**: The `deploy_linux.sh` script handles end-to-end environment recreation:
    1. Installs base dependencies (Java 21, Node v20, Docker, Android SDK CLI Tools).
    2. Pulls and starts the backend infrastructure (Postgres, Redis, Kafka, Zookeeper) via `docker-compose`.
    3. Blocks until the Postgres container is healthy using `pg_isready`.
    4. Automatically unpacks the migration zip and executes a `psql` restore into the database.
    5. Restores the Matrix configs and ADB keys so existing device bridges immediately reconnect upon the `backend` container start.
