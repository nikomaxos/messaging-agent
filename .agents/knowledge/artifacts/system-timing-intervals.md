# System Timing and Polling Intervals

## Overview
This document serves as a centralized reference for all scheduled, polled, and interval-based tasks in the system. Knowing these intervals helps prevent race conditions, avoid unnecessary debugging, and understand how quickly state propagates across the event-driven microservices architecture.

## 1. SMPP Session & Connection State

### Edge Node Heartbeat (`ma-smpp-edge`)
- **Interval**: **Every 10 seconds** (`@Scheduled(fixedDelay = 10000)` in `SmppServerService`).
- **Action**: Pushes all active SMPP session IDs and their uptimes to Redis under the hash `smpp:sessions:{systemId}`.
- **TTL**: Keys expire after **30 seconds**. This ensures that if the edge node crashes without gracefully disconnecting clients, the sessions will naturally drop from Redis within 30 seconds, accurately reflecting an offline state.

### Admin Panel UI Polling
- **SMPP Clients Page** (`admin-panel/src/pages/SmppClientsPage.tsx`)
  - **Interval**: **Every 5 seconds** (`refetchInterval: 5000` via React Query).
  - **Action**: Fetches the list of clients and their active sessions from the backend to provide a near real-time dashboard of online/offline status.

### Offline Notification Engine (`legacy-monolith`)
- **Interval**: **Every 60 seconds** (`@Scheduled` in `AlertScheduler`).
- **Action**: Iterates over all active SMPP clients in Postgres and checks Redis for the `smpp:sessions:{systemId}` key. If missing, it triggers an `SMPP_CLIENT_OFFLINE` notification event (e.g. email, push notification).

## 2. Other Core Schedulers

*(Note: Additional background jobs such as SMSC routing retries, matrix syncs, or database cleanups should be documented here as they are discovered or implemented).*
