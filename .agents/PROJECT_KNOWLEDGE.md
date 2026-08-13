# Messaging Agent - Knowledge Vault

This vault contains the context, architecture, and deployment strategy for the `messaging-agent` project. It ensures any instance of Antigravity understands the ecosystem deeply.

## 1. System Architecture

The `messaging-agent` has been migrated from a Modular Monolith to an **Event-Driven Microservices Architecture**:

- **Core Service (`ma-core-service`)**:
  - A Java Spring Boot application (Port 18080).
  - Handles UI CRUD operations, Admin APIs, and pushes active configuration/metadata to Redis.
- **Routing Engine (`ma-routing-engine`)**:
  - A Java Spring Boot application (Port 18081).
  - 100% Event-Driven. Consumes inbound SMS from Kafka, performs O(1) Redis lookups for rate limiting and routing, and dispatches to outbound queues.
- **SMPP Edge Node (`ma-smpp-edge`)**:
  - A Java Spring Boot application (Port 2776 mapped to 2775 locally).
  - Handles TCP ingress/egress. Inbound traffic drops into Kafka `inbound.raw`. Egress traffic is read from `outbound.smpp` and dispatched via Cloudhopper.
- **Legacy Backend (`ma-backend`)**:
  - Still active temporarily for Android WebSocket management (`DeviceWebSocketService`) and Matrix integration (`mautrix-gmessages`).
- **Android Client (`android-app`)**:
  - Handles WebSocket connections back to the server and OTA (Over-The-Air) updates.
- **Admin Panel (`admin-panel`)**:
  - A Vite + React + Tailwind CSS dashboard providing UI for device management, logs, and DevOps operations.

## 2. Advanced Queueing & Rate Limiting Architecture

To handle massive asynchronous burst traffic and fractional rate limiting (e.g. 0.1 TPS), the system employs a custom **Modular Monolith Worker** pattern backed by Kafka and Redis:
- **Zero-Latency OTP Prioritization**: SMPP clients can be assigned Priority 1 (OTP) or Priority 2 (Marketing). The Redis `ZADD` composite score logic (`priority * 10^13 + timestamp`) guarantees OTP messages are mathematically forced to the head of the dispatch queue ahead of any marketing blasts.
- **Fractional Rate Limiting (Minimum Delay Algorithm)**: Redis `PX` expirations calculate mandatory millisecond delays based on `1000 / TPS`. This allows infinitely fractional speeds perfectly bound to Customer Profile + Country + Network + Supplier route combinations.
- **Microservice Decoupling**: High-volume queue processing is isolated in the `ma-dispatcher-worker` container (using `spring.profiles.active=worker`) to prevent web and API threads in the `backend` from being blocked under load.

## 3. Infrastructure (Hetzner + Proxmox)

- **Bare Metal**: Hetzner AX41 Dedicated Server (`65.108.8.252`).
- **Hypervisor**: Proxmox VE 8.
- **Domain**: `globalnetservices.net` (Proxmox UI is at `https://pve.globalnetservices.net:8006`).
- **Network Quirks**: The physical NIC (`enp9s0`) MAC address was spoofed onto the `vmbr0` bridge using `hwaddress` to bypass Hetzner's strict MAC filtering.
- **SSL**: Proxmox uses Let's Encrypt ACME with the `pve.globalnetservices.net` domain.
- **DevBox (10.10.10.96)**: An Ubuntu 24.04 VM acting as the primary remote workspace. 
  - **RDP Engine**: We migrated from `xrdp` to **Native GNOME Remote Desktop (Wayland/PipeWire)** on port 3389 with 4096-bit TLS certs, entirely eliminating software rasterization lag. (Note: The OS boots into `graphical.target` because the system daemon requires GDM to be running to bind to port 3389 and provide the remote login screen).
  - **NAT Routing (CRITICAL)**: To reach the DevBox from outside Hetzner, the Proxmox Host MUST be configured to forward incoming traffic on port `53389` to the DevBox IP `10.10.10.96:3389` using `iptables` PREROUTING. Always remember this mapping for external access.
  - **Memory Optimization**: The QEMU Guest Agent is installed to report accurate RAM. Caches are tuned (`vm.swappiness=10`, `vm.vfs_cache_pressure=50`) so Proxmox doesn't misinterpret file cache as used RAM, freeing it up for AI tasks.

## 3. Enterprise Edge Routing & Proxies

Traffic is natively routed at the hypervisor edge using HAProxy to avoid port conflicts and ensure high availability:
1. **Edge Router (HAProxy)**: Installed directly on the Proxmox host (`65.108.8.252`). It listens on ports 80 and 443 and routes TCP/SNI traffic based on the requested domain.
2. **Staging Environment**: 
   - **Domain:** `staging-messaging-agent.globalnetservices.net`
   - **Target:** The DevBox (`10.10.10.96`).
   - **Proxy:** Caddy (`~/caddy-proxy`) routes to the Admin Panel (8081) and the Deploy Agent (8082).
3. **Production Environment**:
   - **Domain:** `messaging-agent.globalnetservices.net`
   - **Target:** Production VM (`10.10.10.192`), a headless Ubuntu Server instance.
   - **Proxy:** Caddy (`~/caddy-proxy`) routes to the Production Admin Panel (8081).
   - **Storage:** The main OS disk must use `.qcow2` format (not `.raw`) to support instantaneous Hypervisor snapshots.

## 4. Deployment Dashboard and CI/CD

- A dedicated DevOps dashboard is built into the `admin-panel` (DeployPage.tsx).
- **Security Check:** The Deploy Page UI dynamically locks down functionality based on the environment. In Production, deployments are disabled, leaving only the "Rollback" functionality accessible.
- **Microservice:** Deployments are triggered via the `deploy-agent` (a Node.js Docker container running on DevBox port 8082).
- **Execution:** When triggered, the `deploy-agent` uses SSH to securely connect to Proxmox and the Production VM to execute commands.
- **Rollbacks:** Before every deployment, an automatic Proxmox VM Snapshot is taken. Rollbacks instantly revert the entire Proxmox disk state (code, database, and containers) to the exact second before deployment.

## 5. Agent Operating Rules

- **Knowledge Retrieval**: Always read this `PROJECT_KNOWLEDGE.md` file when starting a new session to understand the environment.
- **Always verify environment**: Before modifying files or running scripts, check the `NODE_ENV` or hostname (Are you on the local PC, the DevBox 10.10.10.96, or the Proxmox Host?).
- **Avoid destructive commands on Prod**: Rollbacks should be handled by the deployment scripts/Proxmox snapshots, not manual `git reset` by the agent unless explicitly requested.
- **Maintain Aesthetics**: The `admin-panel` UI uses glassmorphism and modern dark mode styling. Do not introduce generic unstyled HTML components.
- **Zero-Trust Credentials (CRITICAL)**: NEVER store plaintext passwords, API keys, or sensitive credentials on the DevBox, Proxmox host, or in any scripts (e.g., `askpass.sh` or setup scripts). Passwords must only be held by the user on their local connecting PC (e.g., in their RDP client or password manager).
- **Continuous Knowledge Vault Updates**: Any time you introduce a new feature, fix a bug, or change architectural concepts (such as timers, polling intervals, or caching logic), you MUST actively update the respective artifacts inside `.agents/knowledge/artifacts/` (e.g., `system-timing-intervals.md`) and their `metadata.json` so the agent always retains accurate memory for future sessions.
