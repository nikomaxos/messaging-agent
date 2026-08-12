# SMPP Client Authentication Pattern

## Overview
In this architecture, SMPP client authentication is decoupled between the Management Plane (Postgres) and the Edge Plane (Redis).

1. **Source of Truth (Postgres)**: The `SmppClient` entities are stored in the PostgreSQL database. This is managed by the Admin Panel UI through the `legacy-monolith` REST API.
2. **Edge Authentication Cache (Redis)**: The `ma-smpp-edge` node (which may be scaled dynamically in Kubernetes) **does not query PostgreSQL** for performance and decoupling reasons. Instead, it expects to find the client's password in Redis.

## Redis Key Structure
The system uses the following Redis key convention for client authentication:
```
config:client:{systemId}:password
```
- If the key exists and the value matches the password sent in the SMPP `bind_transceiver` request, the connection is accepted.
- If the key does not exist, or the value mismatches, the edge node returns an `ERROR: RINVPASWD (0x00e)` and drops the connection.

## Data Synchronization
To bridge the gap between the Source of Truth and the Edge Cache:
- Any backend API responsible for creating, updating, or deleting SMPP clients (e.g. `SmppClientController`) **MUST** push these changes to Redis immediately.
- **Deactivation**: If a client is deactivated via the UI, their key must be deleted from Redis to instantly revoke their access at the edge, without requiring the edge node to poll for state changes.
