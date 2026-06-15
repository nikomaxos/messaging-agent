# Antigravity Initialization Prompt
**Project:** Messaging Agent Gateway
**Description:** A high-performance, dual-routing messaging architecture serving as an SMPP/RCS gateway, integrated with Android devices, a Matrix Synapse bridge, and a comprehensive Admin Panel.

## Instructions for Antigravity
Please read this document carefully. It contains the complete context of the project. You are resuming development on a new Linux PC (Linux Mint 22.3, 12 GB RAM) after migrating from a Windows environment. Your objective is to seamlessly continue resolving bugs and developing features using this accumulated context.

## 1. Architectural Overview

### 1.1 Backend (`/backend`)
- **Stack:** Java 21, Spring Boot 3.2.4, PostgreSQL, Redis, Kafka.
- **Role:** Handles core routing, SMPP server/client connections using Cloudhopper, WebSocket connections to mobile devices, and Matrix integration.
- **Key Mechanisms:**
  - **Kafka SMPP Queueing:** Outbound SMPP traffic is dispatched via a durable, Kafka-backed asynchronous queue. This decoupled architecture prevents message loss during intentional session rebinds or unexpected drops, ensuring automatic retries.
  - **AIT Mitigation:** Built-in Artificially Inflated Traffic mitigation. Ingress logic can dynamically return positive SMPP responses ("Fake Success") and spoofed Delivery Receipts (DLRs) to deceive attackers while dropping traffic in memory.
  - **Dual-Routing System:** Can switch message dispatch between a native WebSocket Android relay and a Matrix Synapse environment (via `mautrix-gmessages`), configurable per device via `routingMode`.

### 1.2 Admin Panel (`/admin-panel`)
- **Stack:** React, Vite, TypeScript, TailwindCSS.
- **Role:** The management interface for devices, SIM inventory, AI chat sessions, and AIT mitigation configurations.
- **Recent Features:**
  - **SIM Management:** Full SIM inventory tracking with manual creation capabilities directly in the Devices tab.
  - **AI Chat Sessions:** Transitioned from a persistent message log to a database-backed session system, allowing isolated threads, archiving, and closure to save API tokens.
  - **AIT Strategy Selection:** UI dropdowns map directly to backend strategies (Drop, Fake Success, Fake DLR).

### 1.3 Android App (`/android-app` & `/app`)
- **Role:** Acts as the edge-node relay.
- **Key Mechanisms:**
  - Monitors and captures Delivery Receipts (DLRs) directly from the Android SQLite messaging databases.
  - **Matrix DLR Reliability:** Implements a 4-hour lookback query on the device to reliably capture delayed delivery reports for Matrix-dispatched messages, ensuring carrier-grade reliability without blocking high-concurrency volumes.

### 1.4 Deployment & Infrastructure
- Designed to run on a Proxmox-virtualized environment with Docker and `docker-compose`.
- **Containers:** Postgres, Redis, Kafka, Zookeeper.

## 2. Recent Development Context & Resolved Issues

When encountering similar code paths, refer to these recently solved challenges:
1. **Kafka Async Rewrite:** Removed synchronous SMPP submits; all submits now go through Kafka to survive transient connection failures.
2. **Matrix DLR SQLite Lag:** We fixed the "Dispatched" status lag by updating the Android SQLite queries. The device now reliably captures delayed DLRs (up to 4 hours old) when routing over Matrix.
3. **WSL & Docker Integration:** Solved local "Permission denied" errors on Ubuntu/WSL involving `systemd=true` conflicts with Docker's proxy. If Docker fails on this Linux host, verify proxy and socket permissions.

## 3. Getting Started
You are now fully contextually aware. A one-paste deployment script (`deploy_linux.sh`) was executed on this machine which automatically:
- Cloned the repository (`experimental/apk-direct-hooks` branch).
- Installed the required tools (Docker, Java 21, Node.js).
- Pulled the docker images and started the infrastructure (`docker compose up -d`).
- Extracted the Windows data backup and restored the PostgreSQL database, Matrix configs, and ADB keys.

Please run `docker compose ps` to ensure the core infrastructure is running, and ask the user what the next task is.
