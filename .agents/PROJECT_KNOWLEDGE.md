# Messaging Agent - Knowledge Vault

This vault contains the context, architecture, and deployment strategy for the `messaging-agent` project. It ensures any instance of Antigravity understands the ecosystem deeply.

## 1. System Architecture

The `messaging-agent` is a multi-component platform designed for messaging routing and device management:

- **Android Client (`android-app`)**:
  - Handles WebSocket connections (`WebSocketRelayClient`) back to the server.
  - Features an OTA (Over-The-Air) update mechanism (`OtaRequestHandlerReceiver`) that downloads and installs APK updates automatically.
  - Listens to Matrix and SMPP events for bidirectional messaging.
- **Backend (`backend`)**:
  - A Java Spring Boot application.
  - Manages `DeviceWebSocketService` for realtime Android interactions.
  - Integrates with Matrix (`MatrixQueueService`, `MatrixRouteService`) and SMPP (`SmppServerService`).
  - Connects to an external `mautrix-gmessages` bridge.
- **Admin Panel (`admin-panel`)**:
  - A Vite + React + Tailwind CSS dashboard.
  - Provides a UI for device management, logs, and DevOps operations (Deployments).

## 2. Infrastructure (Hetzner + Proxmox)

- **Bare Metal**: Hetzner AX41 Dedicated Server (`65.108.8.252`).
- **Hypervisor**: Proxmox VE 8.
- **Domain**: `globalnetservices.net` (Proxmox UI is at `https://pve.globalnetservices.net:8006`).
- **Network Quirks**: The physical NIC (`enp9s0`) MAC address was spoofed onto the `vmbr0` bridge using `hwaddress` to bypass Hetzner's strict MAC filtering.
- **SSL**: Proxmox uses Let's Encrypt ACME with the `pve.globalnetservices.net` domain.
- **DevBox (10.10.10.96)**: An Ubuntu 24.04 VM acting as the primary remote workspace. 
  - **RDP Engine**: We migrated from `xrdp` to **Native GNOME Remote Desktop (Wayland/PipeWire)** on port 3389 with 4096-bit TLS certs, entirely eliminating software rasterization lag.
  - **Memory Optimization**: The QEMU Guest Agent is installed to report accurate RAM. Caches are tuned (`vm.swappiness=10`, `vm.vfs_cache_pressure=50`) so Proxmox doesn't misinterpret file cache as used RAM, freeing it up for AI tasks.

## 3. Dynamic Multi-App Provisioning Strategy

We use a **Proxmox VM Cloning** strategy handled dynamically by our Node.js `deploy-agent`:
1. **Dynamic Registry (`apps.json`)**: Tracks which VMs belong to which app (e.g. `messaging-agent` uses Prod: 100, Staging: 101).
2. **Auto-Provisioning**: The `/api/provision` endpoint fetches `pvesh get /cluster/nextid` and automatically clones Base Template `9000` into two new VMs (Staging & Production) for any new application requested.
3. **Staging Parity**: Staging environments can be hard-cloned directly from Production VMs at any time to ensure 100% parity before testing.

## 4. Deployment Dashboard and CI/CD

- A dedicated DevOps dashboard is built into the `admin-panel` (DeployPage.tsx).
- It communicates with the `deploy-agent` (a Node.js backend) to execute deployment scripts (`deploy.sh`) inside VMs via `qm guest exec`.
- The dashboard supports multi-app selection, dynamic provisioning, live log streaming (SSE), and 1-click Rollbacks to previous Proxmox snapshots.

## 5. Agent Operating Rules

- **Knowledge Retrieval**: Always read this `PROJECT_KNOWLEDGE.md` file when starting a new session to understand the environment.
- **Always verify environment**: Before modifying files or running scripts, check the `NODE_ENV` or hostname (Are you on the local PC, the DevBox 10.10.10.96, or the Proxmox Host?).
- **Avoid destructive commands on Prod**: Rollbacks should be handled by the deployment scripts/Proxmox snapshots, not manual `git reset` by the agent unless explicitly requested.
- **Maintain Aesthetics**: The `admin-panel` UI uses glassmorphism and modern dark mode styling. Do not introduce generic unstyled HTML components.
