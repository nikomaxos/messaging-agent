# Messaging Agent - Knowledge Vault

This vault contains the context, architecture, and deployment strategy for the `messaging-agent` project. It ensures any instance of Antigravity understands the ecosystem deeply.

## 1. System Architecture

The `messaging-agent` has been migrated from a Modular Monolith to an **Event-Driven Microservices Architecture**:

- **Core Service (`ma-core-service`)**:
  - A Java Spring Boot application (Port 18080).
  - Handles UI CRUD operations, Admin APIs (including Traffic Analytics, DLQ, Throughput, Reports, System Logs, Audit Logs, and **Billing Tariffs**), and pushes active configuration/metadata to Redis.
  - Manages the single source of truth for `account`, `username`, `account_billing`, `tariff_plan`, and routing entities like `country` and `network` in PostgreSQL. Uses `BillingSyncJob` to continuously synchronize real-time balances from Redis back to PostgreSQL every 30 seconds.
  - **Routing Metadata & Prefix Protection**: The system maintains a global registry of routing prefixes. When syncing prefixes from external sources (via `prefix-updater`), a strict **"Global Prefix Protection"** rule is enforced: an incoming prefix is injected into the database ONLY if it does not already exist anywhere in the system. This guarantees that manual prefix relocations (e.g., due to number portability) are never overwritten by automated syncs.
  - **Country Regulations**: The `Country` entity tracks regulatory attributes such as `quietHoursStart`, `quietHoursEnd`, and `hasDndList`, which are strictly preserved during database merges.
- **Routing Engine (`ma-routing-engine`)**:
  - A Java Spring Boot application (Port 18081).
  - 100% Event-Driven. Consumes inbound SMS from Kafka, performs O(1) Redis lookups for rate limiting and routing, and dispatches to outbound queues.
  - Features a **Real-Time Rating Engine** (`RatingEngine.java`) that executes atomic Lua scripts (`EVAL`) directly in Redis to deduct `PREPAID` balances (`balance:acc:{accountId}`) before messages are queued, dropping messages instantly on insufficient funds. The `accountId` is resolved via a Redis mapping `client_to_account:{systemId}` (which maps the SmppClient's linked Username back to its parent Account).
- **SMPP Edge Node (`ma-smpp-edge`)**:
  - A Java Spring Boot application (Port 2776 mapped to 2775 locally).
  - Handles TCP ingress/egress. Inbound traffic drops into Kafka `inbound.raw`. Egress traffic is read from `outbound.smpp` and dispatched via Cloudhopper.
  - **Connection Optimization**: SMSC Supplier connections rely exclusively on `EnquireLink` heartbeats to detect and sever ghost connections. Hard lifetime cutoffs (`maxSessionLifetime`) are disabled to avoid unnecessarily dropping healthy sessions.
  - **Active Connection State Tracking**: Extracts and syncs real-time connection state to Redis in a Hash (e.g. `smpp:sessions:{systemId}` with fields `0`, `1`, etc.), formatting as `bindType|uptimeSeconds|ipAddress`. Remote IP extraction is achieved via reflection on the Cloudhopper `SmppSession` Netty channel.
  - **Heartbeat & Remote Management**: `ma-smpp-edge` continuously publishes its `uptimeStartedAt` to `smpp:edge:heartbeat` in Redis every 10 seconds. The Admin Panel API (`ma-core-service`) uses this to report accurate uptime. Additionally, `ma-core-service` can orchestrate an inner-server restart by setting the `smpp:edge:command` Redis key to `RESTART`, which `ma-smpp-edge` polls and executes without restarting the entire Docker container.
- **AI Service (`ma-ai-service`)**:
  - A Java Spring Boot application providing an LLM-powered context-aware assistant.
  - Features real-time system metrics ingestion (calling `/api/system/health` on the Core Service) to give the LLM real-world platform context.
- **Android Client (`android-app`)**:
  - Handles WebSocket connections back to the server and OTA (Over-The-Air) updates.
  - **RCS Anti-Spam & Auto-Heal**: The `:bot` process (`BotService`) continuously monitors `bugle_db` for Delivery Receipts (DLRs). If it detects a "Fake Offline" block (where the Google Jibe server rate-limits capability lookups, causing Google Messages to force an SMS fallback or fail with status 8/9), it triggers a **DLR-Safe Auto-Heal**. The auto-heal waits up to 15s for pending DLRs to drain, then safely clears `Carrier Services` (to drop the corrupted capability cache) and restarts the app to re-verify with Jibe.
  - **SMS Layer 1 Bypass**: The AOSP `SmsUsageMonitor` rate limits (30 SMS / 30 mins) are globally disabled (`sms_outgoing_check_max_count 999999`) on boot and re-enforced every 30 seconds via the `keepalive.sh` script to serve as a safety net during temporary SMS fallbacks.
- **Admin Panel (`admin-panel`)**:
  - A Vite + React + Tailwind CSS dashboard providing UI for device management, logs, and DevOps operations.
  - **Component Architecture**: Uses shared components to ensure consistency across the UI. For instance, `SmppClientFormFields.tsx` acts as the single source of truth for SMPP client attributes. It is used both in the SMPP Clients management page and in the dynamic "Account Creation" flow (which pauses to collect SMPP credentials for `smppEnabled` usernames before finalizing account creation).
  
## 2. Advanced Routing & Rules Engine

- **Rules Engine (`RoutingRuleService`)**:
  - Built into `ma-core-service`, it evaluates incoming messages against customizable rules before standard routing.
  - Supports Triggers (Conditions) based on Regex, Contains, Starts With, or Exact Match on Source, Destination, System ID, or Message Text.
  - Actions include: Rewrite Text, Rewrite Source, Force SMSC, Fake DLR, and Drop.
- **Fake DLR Billing Concept**:
  - When a rule triggers a `FAKE_DLR` action (e.g. `DELIVRD`), the backend (`SmsInboundConsumer`) intercepts the message, emulates the DLR, and persists the specified fake status to the database (`isEmulated = true`).
  - **Billing Impact:** The system's standard billing logic charges customers based on the final status (i.e. `DELIVRD`). By intentionally faking a `DELIVRD` status, the platform successfully charges the customer the selling price for blocked or dropped traffic, monetizing spam filtering.

## 2.5 Security & User Bans
- **Ban Functionality**: A `Username` can be globally banned. This status is synced to Redis via `RedisConfigSyncService` (`config:client:{systemId}:banned`). 
- **Effect of Ban**: The `SmppServerService` reads this flag and instantly drops incoming `BIND` requests or drops active `SUBMIT_SM` traffic from banned users. Future API and Web Portal modules will also check this flag to deny authentication.

## 3. Advanced Queueing & Rate Limiting Architecture

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
   - **Target:** Production K3s Cluster (`10.10.10.193` - `10.10.10.195`).
   - **Proxy:** Edge routing through HAProxy on the Proxmox Host, passing through to K3s Traefik.
4. **SMPP Edge Routing (Live Proxy)**:
   - **Domain:** `smpp.globalnetservices.net`
   - **Proxy Target:** All TCP traffic (ports 2775/2776) and HTTP (port 8085 for UI/API) routed via HAProxy to a dedicated Node.js proxy VM at **`10.10.10.105`**.
   - **Management:** The live SMPP proxy targets can be managed via the REST API at `http://10.10.10.105:8086/api/config` or `http://10.10.10.105:8086/api/status`. (Note: The admin API listens internally on port 8086 on the proxy VM).
   - **Deployment:** The live proxy runs via `pm2` under the `ubuntu` user on `10.10.10.105`. The code in `~/Development/smpp-proxy/` on the DevBox is just a local copy. To deploy proxy updates, you must use `scp` to copy `server.js` to `ubuntu@10.10.10.105:~/smpp-proxy/server.js` and run `pm2 restart smpp-proxy` via SSH.


## 4. Deployment Dashboard, CI/CD, and Disaster Recovery

- A dedicated DevOps dashboard is built into the `admin-panel` (DeployPage.tsx and BackupRestorePage.tsx).
- **Disaster Recovery (Google Drive Backup)**: The `deploy-agent` is equipped with `rclone`. It can SSH into production, execute a `pg_dump` on the `ma-postgres-0` database, and push the snapshot to a configured Google Drive folder. It also supports one-click streaming restores directly from the UI.
  - **Auto-Backup Scheduler**: A daily auto-backup can be toggled on/off from the UI with a configurable hour (UTC). The backend checks every 60 seconds if the current UTC hour matches and triggers one backup per day. Auto-backup files are prefixed `messagingagent_autobackup_` and manual ones `messagingagent_backup_`.
  - **Retention Policy**: After every successful backup (manual or auto), a smart cleanup runs: keep ALL backups from the last 7 days, keep 1 per week for days 8–30, keep 1 per month forever beyond 30 days. Old files are deleted from Google Drive via `rclone deletefile`.
  - **Configuration Persistence**: The Service Account JSON, Google Drive folder ID (`root_folder_id`), auto-backup enabled flag, and scheduled hour are all persisted in `/app/backup-data/backup-config.json` inside the deploy-agent container, backed by a named Docker volume (`backup_config`) to survive container rebuilds.
  - **Folder Browser**: The UI includes an interactive Google Drive folder browser (`/api/backup/browse`) that uses direct JWT-signed Google Drive REST API calls (via the SA credentials + Node.js `crypto`) to list shared folders and subfolders. The selected folder's ID is stored as rclone's `root_folder_id`, making all rclone operations relative to that folder.
- **Security Check:** The Deploy Page UI dynamically locks down functionality based on the environment. In Production, deployments are disabled, leaving only the "Rollback" functionality accessible.
- **Microservice:** Deployments and Backups are triggered via the `deploy-agent` (a Node.js Docker container running on DevBox port 8082). In **Staging**, Caddy routes `/api/deploy/*` and `/api/backup/*` to this service. In **Production**, the admin panel's `nginx.conf` proxies these same paths directly to the DevBox (`10.10.10.96:8082`), since the deploy-agent runs only on the DevBox.
- **Execution:** When triggered, the `deploy-agent` uses SSH to securely connect to Proxmox and the Production VM to execute commands.
- **Rollbacks:** Before every deployment, an automatic Proxmox VM Snapshot is taken. Rollbacks instantly revert the entire Proxmox disk state (code, database, and containers) to the exact second before deployment.

## 5. Agent Operating Rules

- **Knowledge Retrieval**: Always read this `PROJECT_KNOWLEDGE.md` file when starting a new session to understand the environment.
- **Always verify environment**: Before modifying files or running scripts, check the `NODE_ENV` or hostname (Are you on the local PC, the DevBox 10.10.10.96, or the Proxmox Host?).
- **Avoid destructive commands on Prod**: Rollbacks should be handled by the deployment scripts/Proxmox snapshots, not manual `git reset` by the agent unless explicitly requested.
- **Maintain Aesthetics**: The `admin-panel` UI uses glassmorphism and modern dark mode styling. Do not introduce generic unstyled HTML components.
- **Zero-Trust Credentials (CRITICAL)**: NEVER store plaintext passwords, API keys, or sensitive credentials on the DevBox, Proxmox host, or in any scripts (e.g., `askpass.sh` or setup scripts). Passwords must only be held by the user on their local connecting PC (e.g., in their RDP client or password manager).
- **Continuous Knowledge Vault Updates (CRITICAL ARCHITECT MANDATE)**: 
  - **On every new prompt**: You MUST actively consult the knowledge vaults (`PROJECT_KNOWLEDGE.md` and `.agents/knowledge/artifacts/*`) to guarantee you are operating with the latest context and rules.
  - **On every final reply**: Before concluding your response or task, the Architect persona MUST evaluate if the actions just performed (even quick patches, bug fixes, or minor architectural tweaks) require an update to the vaults.
  - You MUST write the updated knowledge into the vaults immediately. This ensures the vaults are ALWAYS perfectly synchronized with reality. No matter what happens or how small the patch, you always perform this continuous read/write cycle.
- **Mandatory Container Rebuilds (CRITICAL)**: Whenever you modify source code, configuration files (e.g. `nginx.conf`), Dockerfiles, or dependencies (`package.json`, `pom.xml`) for a containerized service (such as `admin-panel`, `deploy-agent`, `core-service`, etc.), you MUST explicitly rebuild and restart that container in the Staging environment (e.g., `docker compose up -d --build admin-panel`) before completing your task. NEVER assume hot-reloading is active or sufficient for compiled/static assets.
- **⛔ NEVER DEPLOY TO PRODUCTION DIRECTLY (ABSOLUTE RULE)**: The agent is strictly **FORBIDDEN** from SSH-ing into production nodes (`10.10.10.193`, `10.10.10.194`, `10.10.10.195`) to run deployment scripts (`deploy-k8s.sh` or any equivalent). The agent may ONLY deploy to the **Staging environment** (DevBox `10.10.10.96`). Production deployments are the USER's exclusive responsibility, triggered via the Staging Admin Panel Deploy Page UI. **There are ZERO exceptions to this rule.** If the user asks to "deploy", always deploy to Staging and inform them it is ready for testing. Never assume "deploy" means "deploy to production".
- **⛔ MANDATORY VAULT-UPDATE GATE (NON-NEGOTIABLE)**: You are FORBIDDEN from running `git commit` or ending your turn UNLESS you have first evaluated whether `.agents/PROJECT_KNOWLEDGE.md` needs to be updated to reflect the work you just performed. If the work introduced, changed, or removed ANY architectural concept, feature, configuration, timing interval, file structure, API endpoint, infrastructure detail, or operational procedure, you MUST update the vault BEFORE committing. There are ZERO exceptions. Forgetting is not acceptable. This rule exists because vault updates were missed in the past and the user has explicitly mandated this safeguard. Treat every `git commit` as a two-step operation: (1) update vault if needed, (2) commit.
- **DEEP RESEARCH BEFORE CONCLUSIONS**: Always perform deep research on the codebase and data (logs, endpoints, configurations) before reaching a conclusion or presenting a root cause for a problem. Never assume a requested feature (like a UI toggle) is the underlying solution to a technical failure without verifying it firsthand.
- **COMPREHENSIVE QUESTION ANSWERING**: Always answer EVERY single question or point raised by the user in a prompt. When the user asks multiple things (e.g. 2-3 questions), it is strictly forbidden to skip or ignore any of them. Address each point explicitly in your response.
- **Mandatory QA Validation & Architect Sign-Off (CRITICAL)**: Before concluding ANY request or declaring a fix complete, the internal QA persona MUST perform a comprehensive, end-to-end validation of the entire scope of the change. This means checking that both backend logic AND frontend UI assets are fully synced across all relevant nodes (e.g., preventing partial deployments where a backend file is copied to a remote VM but the frontend HTML/JS is forgotten). The QA persona must explicitly report its findings to the Architect persona internally. The Architect MUST ONLY give the final "Approve" and release the response to the user if the solution is 100% bug-free, fully deployed across all necessary layers, and functionally complete. NEVER deliver partial fixes or assume a deployment step was successful without verifying its actual impact on the live environment.

## 6. Production Upgrades & Updates (CRITICAL GUIDELINES)

To ensure the production system **never hangs** and the USER retains full control over what goes live, the following **MANDATORY deployment workflow** must be followed **WITHOUT EXCEPTION**:

### ⛔ Mandatory Deployment Workflow (Staging → User Approval → Production)

| Step | Who | Action |
|------|-----|--------|
| 1 | **Agent** | Makes code changes and pushes to `git` (origin main). |
| 2 | **Agent** | Deploys **ONLY to Staging** (DevBox `10.10.10.96`). |
| 3 | **Agent** | Notifies the USER: _"Changes are deployed to Staging and ready for your testing at `staging-messaging-agent.globalnetservices.net`."_ |
| 4 | **USER** | Tests the changes on the Staging environment. |
| 5 | **USER** | Triggers Production deployment via the **Staging Admin Panel → Deploy Page UI**. |

> **VIOLATION**: Any agent action that directly executes deployment scripts on production nodes (`10.10.10.192`–`10.10.10.195`) is a **critical violation** of this workflow. This includes SSH commands, `kubectl` rollouts, or running `deploy-k8s.sh` against the production cluster. The agent does NOT have authorization to perform these actions under ANY circumstances.

### Additional Safety Rules
1. **Verify Dependencies Before Booting Apps:** If databases or message queues (like Kafka or Zookeeper) have dirty state (e.g. `InconsistentClusterIdException`) or fail healthchecks, ALL downstream microservices will hang or crash loop. Always ensure stateful services are healthy before bringing up web or processing nodes.
2. **Handle Stale Docker Volumes:** When drastically changing environments or resetting local clusters (e.g., recreating Kafka), ensure you wipe the old docker data volumes (e.g. `docker compose down -v` or `docker volume rm ...`) if they contain stale cluster IDs that will prevent startup.
3. **Instant Rollback Safety Net:** Leverage Proxmox VM Snapshots via the DevOps Dashboard. Before a major code change goes to `10.10.10.192` (Production), ensure a snapshot was taken. If Production hangs, instantly trigger a rollback rather than trying to hotfix live.

## 7. Multi-Agent Environment Workflow

Whenever the USER requests a new implementation, feature, or complex bug fix, you MUST simulate a multi-agent environment internally to ensure high-quality, tested deliverables:
1. **The Team:** Assume the roles of **1 Architect**, **1 Coder**, **1 QA**, and **1 Web/Graphics Developer**.
2. **Collaboration & Reporting:** Each persona must contribute to the plan and report to each other in your thought process or implementation plan.
   - *Architect:* Designs the solution, coordinates the team, and holds final approval authority.
   - *Coder:* Writes the backend/logic implementation.
   - *Web/Graphics Developer:* Handles UI/UX, aesthetics, and frontend implementation.
   - *QA:* Develops the testing strategy and actively verifies the work.
3. **Testing First:** EVERY solution must be rigorously tested (by the QA persona) and confirmed to be working in the environment before it is presented to the user.
4. **Final Approval:** The Architect must explicitly approve and accept the deliverables of the rest of the team before concluding the task.

## 8. SSH Access Map & Security

All infrastructure access is **SSH key-based only**. Password authentication is disabled on all K3s nodes. The DevBox has passwordless sudo for `nick`.

### Node Inventory (Proxmox VMs)

| VMID | Hostname | IP | User | Role |
|------|----------|-----|------|------|
| 100 | DevBox | 10.10.10.96 | `nick` | Staging environment, development workspace |
| 301 | k3s-master-1 | 10.10.10.193 | `ubuntu` | K3s master node (Production) |
| 302 | k3s-worker-1 | 10.10.10.194 | `ubuntu` | K3s worker node (Production) |
| 303 | k3s-worker-2 | 10.10.10.195 | `ubuntu` | K3s worker node (Production) |
| — | pve | 65.108.8.252 | `root` | Proxmox hypervisor host |

> **No LXC containers exist.** Only the 4 VMs listed above.

### SSH Config (DevBox `~/.ssh/config`)

Host aliases are configured on the DevBox for clean access:
- `ssh k3s-master` → `ubuntu@10.10.10.193`
- `ssh k3s-worker-1` → `ubuntu@10.10.10.194`
- `ssh k3s-worker-2` → `ubuntu@10.10.10.195`
- `ssh proxmox` → `root@65.108.8.252`

### Access Capabilities

| From → To | Method | Sudo | Notes |
|-----------|--------|------|-------|
| DevBox → K3s nodes | SSH key (`~/.ssh/id_rsa`) | Passwordless (`ubuntu` user) | Used by `deploy-agent` and agent |
| DevBox → Proxmox | SSH key (`~/.ssh/id_rsa`) | Already root | Used for VM management |
| DevBox localhost | Direct | Passwordless (`/etc/sudoers.d/nick`) | Agent can run `docker`, `apt`, etc. |
| K3s inter-node | SSH key | Passwordless | Used by `deploy-k8s.sh` for image sync |
| K3s → DevBox | ❌ BLOCKED | — | No reverse SSH access (by design) |

### Security Hardening

- **Password auth disabled** on all K3s nodes (`PasswordAuthentication no` in `/etc/ssh/sshd_config`).
- **SSH key only**: All access uses the DevBox RSA key pair at `~/.ssh/id_rsa`.
- **Zero-Trust**: No passwords stored anywhere on any node. The user's RDP password is held only on their local PC.
- **Keepalive**: SSH config includes `ServerAliveInterval 60` to prevent dropped connections.

## 9. Database Schema and Migration Safety (CRITICAL)

To prevent downtime, missing data in the UI, or disconnections from upstream SMSCs (like Melrose), the following database rules MUST be strictly enforced:
1. **Never Mix Manual SQL and Flyway**: Do NOT execute manual `psql` `ALTER TABLE` commands if you are also creating a Flyway migration (`VXX__...sql`). Write the Flyway migration and apply it properly by rebuilding the `core-service` container (`docker compose up -d --build core-service`).
2. **Idempotent Migrations**: EVERY Flyway script must be strictly idempotent. Always use `ADD COLUMN IF NOT EXISTS` and `DROP COLUMN IF EXISTS` to prevent Flyway from crashing the Spring Boot application if the database state is slightly out of sync.
3. **Downstream Service Checks**: `smpp-edge`, `routing-engine`, and `device-gateway` rely entirely on `core-service` for their configurations. If `core-service` crashes due to a bad migration, `smpp-edge` will fail to load suppliers and disconnect from upstream. If `core-service` is taken down or restarted, you MUST verify the health of `smpp-edge` and restart it (`docker restart ma-smpp-edge`) if it failed to reconnect to the core API.
4. **Data Backups**: Before performing any complex migrations, take a backup of the `messagingagent` database using `pg_dump` to ensure data is never lost.

### Versioning & Releasing (Mandatory Rule)
- A **Strict Semantic Versioning** workflow is enforced to prevent environment mismatch (e.g., Staging 2.3.2 but Production 2.4.1). 
- **Production MUST NEVER exceed the Staging Version**. All updates must incrementally flow through Staging first.
- **Fully Automated Bumping**: The version bumping process is now fully automated and built into the deployment flow. When the USER triggers a Production deployment from the Admin Panel, the `deploy-agent` automatically parses the current version, increments the patch version (e.g., `2.4.4` to `2.4.5`), safely bumps all `package.json` and `pom.xml` files using `./bump-version.sh`, commits the new version, and pushes it to `origin main` before the deployment rolls out.
- The agent DOES NOT need to run `./bump-version.sh` manually anymore.
- Whenever versions are bumped during deployment, you MUST rebuild the container on Staging (`docker compose up -d --build admin-panel`) so the UI displays the updated version correctly.

## 10. Common Issues & Troubleshooting (Vault)

### 1. Missing Data in Admin Panel (Message Tracking / Entities)
- **Symptom**: Tables like Message Tracking, SMPP Clients, SMSC Suppliers, etc., show NO data (empty tables), but the database has data and Nginx logs show `504 Gateway Timeout` or `110: Operation timed out`.
- **Root Cause 1 (Hibernate Proxies)**: Jackson serialization fails with `InvalidDefinitionException: No serializer found for class org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor`. This happens when Spring Data JPA returns a lazy-loaded proxy (e.g., `MessageLog` self-referencing `parentMessage`). Because it fails silently in the background (HTTP 500 in core-service), the frontend receives no JSON array and renders an empty table.
- **Fix**: Add `@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})` to the **Class level** of the JPA `@Entity` (e.g. `MessageLog.java`).
- **Root Cause 2 (K8s Service Ports)**: Check if the Kubernetes Service exposes the correct port (e.g. `ma-api-gateway` must expose 3000, not 9090) so Nginx `proxy_pass` can route correctly in Production.

### 2. Persistent Background Deployments
- **Architecture**: The `deploy-agent` backend (`server.js`) now maintains a global background state for active deployments. The deployment execution is no longer tied to a specific HTTP GET request lifecycle.
- **Triggering**: Deployments are initiated via a standard `POST /api/deploy/trigger` request.
- **Monitoring**: The frontend (`DeployPage.tsx`) connects to `GET /api/deploy/stream` via EventSource on mount. This instantly syncs the UI with the active background deployment (fetching full log history and progress). This means agents can trigger deployments server-side, and the user will see the live progress in their browser, even if they refresh or navigate away.
- **BUILD DIRECTIVE**: When running long commands like `mvn clean package`, always run them in batch mode (`-B`) and monitor for hangs. If a build process hangs, restart it or troubleshoot the blockage. Report status periodically if it runs in the background.

### 3. Deploy Agent & Shell Execution Issues
- **Symptom 1 (Syntax Error in shell):** `ash: syntax error: unexpected "("` when executing inline scripts (like `NEXT_PATCH=\$((\$V_PATCH + 1))`).
  - **Root Cause**: When building the command inside a Node.js template literal (\` \`), writing `\\$` evaluates to a literal string starting with `\`, which prevents the shell from substituting the variable. E.g., `awk` sees `print \$4`, causing it to crash and leaving variables empty.
  - **Fix**: Use `\$` inside the JS template string. This properly escapes the JS interpolation and sends a pure `$` to the shell, allowing `awk '{print $4}'` or shell arithmetic to execute correctly.
- **Symptom 2 (Git Push Fails):** `git@github.com: Permission denied (publickey)` when the deploy-agent container tries to push to Github.
  - **Root Cause**: The docker-compose mounts the host's `~/.ssh` to `/app/.ssh_host:ro` instead of `/root/.ssh`. When `git push` runs, it uses the container's root user which has no SSH key, so it fails.
  - **Fix**: Explicitly tell SSH which identity file to use during the push by setting the environment variable in the command: `GIT_SSH_COMMAND="ssh -i /app/.ssh_host/id_rsa -o StrictHostKeyChecking=no" git push origin main`.

## 11. SMPP PROXY Protocol (SSL Termination)

- **Source IP Visibility**: To support SSL termination through an external TCP proxy (like Nginx `stream` or a custom Node.js proxy) without losing the original client's IP, `ma-smpp-edge` supports **PROXY Protocol v1**.
- **Implementation**: A custom Netty 3 `ProxyProtocolDecoder` is injected dynamically (via Reflection) into the `DefaultSmppServer` pipeline during startup (`SmppServerService.java`). 
- **Behavior**: If a connection starts with `PROXY TCP4 ...`, the decoder extracts the real IP, stores it as a Netty `Channel` attachment, and passes it to the Cloudhopper session. If the connection is a direct SMPP bind (without the proxy header), the decoder safely ignores the traffic and allows it to proceed natively.
- **Proxy Configuration**: Any proxy sitting in front of `ma-smpp-edge` (e.g. Melrose custom Node proxy or Nginx) must explicitly send the PROXY protocol header (`PROXY TCP4 <src> <dst> <sport> <dport>\r\n`) upon connection establishment if it wants the backend to log the true client IP.

## 12. Automated Security Scanner & AI Auto-Patching

- **Scheduled OSV Scans (`ma-security-service`)**: The `CveScannerService` runs a scheduled cron job (or can be manually triggered via `POST /api/cve/scan`) that queries the public Google Open Source Vulnerabilities (OSV) API (`api.osv.dev`) for core dependencies (e.g., `spring-boot-starter-web`, `kafka-clients`, `jackson-databind`).
- **Redis Duplicate Prevention**: Discovered CVEs are cached in Redis (`security:cve:seen:{cveId}`) to ensure the system only alerts the admins once per vulnerability.
- **Actionable Notifications**: New vulnerabilities are dispatched via Kafka (`notifications.alerts`) as JSON payloads containing actionable webhook buttons. 
- **Autonomous AI Remediation (`ma-ai-service`)**: The "Auto-Patch via AI" button hits the `POST /api/ai-agent/tasks/auto-patch` webhook on the AI service. This immediately initializes a new `AiChatSession` titled with the CVE ID, injecting a system prompt that orders the platform's internal AI Agent to analyze the codebase, locate the vulnerable dependency, and automatically propose or apply the patch. The admin can monitor this autonomous execution live via the Admin Panel's AI Chat UI.
