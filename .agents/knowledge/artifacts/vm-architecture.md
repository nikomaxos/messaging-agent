# Virtual Machine Architecture & Roles

## Overview
The `messaging-agent` ecosystem is hosted on a Hetzner AX41 Dedicated Server (`65.108.8.252`) running Proxmox VE 8. The environment is split across several VMs, separating development/staging from production, and splitting production workloads between Docker Compose (for legacy monolithic components and databases) and a Kubernetes (K3s) cluster for scalable microservices.

## VM Inventory

### 1. Proxmox Bare Metal Host (`65.108.8.252`)
- **Role**: Hypervisor and Edge Router.
- **Key Services**: HAProxy edge routing (Routes TCP/SNI traffic based on domains: `messaging-agent.globalnetservices.net` and `staging-messaging-agent.globalnetservices.net`).
- **Network**: Manages the `vmbr0` bridge (MAC address spoofed for Hetzner restrictions).

### 2. DevBox / Staging (VMID: 100)
- **IP Address**: `10.10.10.96`
- **Role**: Primary Development Workspace & Staging Environment.
- **Key Services**:
  - **Remote Desktop**: Native GNOME Remote Desktop (Wayland/PipeWire) on port 3389.
  - **Deploy Agent**: Node.js microservice (`ma-deploy-agent`) running on port 8082, orchestrating deployments to K3s and Production.
  - **Staging Proxy**: Caddy server (`~/caddy-proxy`) proxying requests to the Admin Panel and Deploy Agent.
  - **Staging Environment**: Runs Staging versions of all microservices and databases via Docker Compose.

### 3. Production Legacy / DBs (VMID: 200)
- **Name**: `messaging-agent-prod`
- **IP Address**: `10.10.10.192`
- **Role**: Core Database, Message Broker, and Legacy Monolith hosting.
- **Key Services** (Deployed via `docker-compose`):
  - **Databases**: PostgreSQL (Main DB), Redis (Edge Cache & Session State).
  - **Brokers**: Kafka & Zookeeper (Message Bus).
  - **Legacy Backend (`ma-backend`)**: Handles WebSocket connections for Android devices, Matrix DLR syncing, and SMPP Client management APIs.
  - **Admin Panel**: The Production Vite React UI.
  - **Caddy Proxy**: Proxies port 8081 for the Admin Panel.

### 4. K3s Master Node (VMID: 301)
- **Name**: `k3s-master-1`
- **IP Address**: `10.10.10.193`
- **Role**: Control Plane for the Production Kubernetes Cluster.
- **Key Services**: Runs the Kube API Server and orchestrates workloads. It also serves as the target for the `deploy-agent` SSH connections to execute `kubectl apply`.

### 5. K3s Worker Nodes (VMID: 302, 303)
- **Names**: `k3s-worker-1` (`10.10.10.194`), `k3s-worker-2` (`10.10.10.195`)
- **Role**: Compute nodes for highly available microservices.
- **Workloads (Pods)**:
  - `ma-core-service`: UI CRUD operations, Admin APIs.
  - `ma-routing-engine`: Consumes from Kafka, evaluates Redis logic, dispatches SMS.
  - `ma-smpp-edge`: TCP ingress/egress for SMPP clients (binds, unbinds, heartbeats).
  - `ma-rcs-mautrix`: RCS Integration.
- **Note**: The microservices in K3s are configured to connect back to the Databases and Brokers hosted on VMID 200 (`10.10.10.192`) via Endpoints defined in `00-external-services.yaml`.
