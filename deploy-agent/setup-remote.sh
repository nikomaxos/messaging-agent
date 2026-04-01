#!/bin/bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════════
# Messaging Agent — Remote Server Setup Script
#
# Run this on a fresh Linux server (Ubuntu 22.04+ / Debian 12+)
# to set up the full messaging agent stack with auto-deploy.
#
# Usage:
#   curl -sL https://raw.githubusercontent.com/nikomaxos/messaging-agent/main/deploy-agent/setup-remote.sh | bash
#   — OR —
#   wget -qO- https://raw.githubusercontent.com/nikomaxos/messaging-agent/main/deploy-agent/setup-remote.sh | bash
# ═══════════════════════════════════════════════════════════════════

REPO_URL="https://github.com/nikomaxos/messaging-agent.git"
INSTALL_DIR="/opt/messaging-agent"
DEPLOY_BRANCH="main"

echo "═══════════════════════════════════════════════════════════"
echo "  Messaging Agent — Remote Server Setup"
echo "═══════════════════════════════════════════════════════════"

# ─── Step 1: Install Docker if not present ──────────────────────
if ! command -v docker &> /dev/null; then
    echo "[1/6] Installing Docker..."
    curl -fsSL https://get.docker.com | sh
    systemctl enable docker
    systemctl start docker
else
    echo "[1/6] Docker already installed ✓"
fi

# ─── Step 2: Install Docker Compose plugin if not present ───────
if ! docker compose version &> /dev/null; then
    echo "[2/6] Installing Docker Compose plugin..."
    apt-get update -qq && apt-get install -y -qq docker-compose-plugin
else
    echo "[2/6] Docker Compose already installed ✓"
fi

# ─── Step 3: Install Git if not present ─────────────────────────
if ! command -v git &> /dev/null; then
    echo "[3/6] Installing Git..."
    apt-get update -qq && apt-get install -y -qq git
else
    echo "[3/6] Git already installed ✓"
fi

# ─── Step 4: Clone the repo ────────────────────────────────────
if [ -d "$INSTALL_DIR" ]; then
    echo "[4/6] Repo already exists at $INSTALL_DIR — pulling latest..."
    cd "$INSTALL_DIR"
    git fetch origin "$DEPLOY_BRANCH"
    git reset --hard "origin/$DEPLOY_BRANCH"
else
    echo "[4/6] Cloning repo to $INSTALL_DIR..."
    git clone -b "$DEPLOY_BRANCH" "$REPO_URL" "$INSTALL_DIR"
    cd "$INSTALL_DIR"
fi

# ─── Step 5: Create .env file ──────────────────────────────────
ENV_FILE="$INSTALL_DIR/.env"
if [ ! -f "$ENV_FILE" ]; then
    echo "[5/6] Creating .env file..."
    
    # Generate a random webhook secret
    WEBHOOK_SECRET=$(openssl rand -hex 32)
    JWT_SECRET=$(openssl rand -hex 32)
    ADMIN_PASS=$(openssl rand -base64 16 | tr -d '=/+' | head -c 16)
    
    cat > "$ENV_FILE" << EOF
# ─── Deploy Agent ──────────────────────────────────────
WEBHOOK_SECRET=$WEBHOOK_SECRET
DEPLOY_BRANCH=main
DEPLOY_COOLDOWN=60

# ─── Backend ───────────────────────────────────────────
JWT_SECRET=$JWT_SECRET
ADMIN_USER=admin
ADMIN_PASS=$ADMIN_PASS

# ─── Database ──────────────────────────────────────────
DB_HOST=postgres
DB_PORT=5432
DB_NAME=messagingagent
DB_USER=msgagent
DB_PASS=msgagent
EOF
    
    echo ""
    echo "  ┌──────────────────────────────────────────────────────┐"
    echo "  │  ⚠️  IMPORTANT: Save these credentials!              │"
    echo "  │                                                      │"
    echo "  │  Webhook Secret: $WEBHOOK_SECRET"
    echo "  │  Admin Password: $ADMIN_PASS"
    echo "  │  JWT Secret:     (in .env file)                      │"
    echo "  │                                                      │"
    echo "  │  Add the Webhook Secret to GitHub:                   │"
    echo "  │  Repo → Settings → Webhooks → Add webhook            │"
    echo "  │  URL: http://YOUR_SERVER_IP:9000/webhook             │"
    echo "  │  Secret: (use the Webhook Secret above)              │"
    echo "  │  Events: Just the push event                         │"
    echo "  └──────────────────────────────────────────────────────┘"
    echo ""
else
    echo "[5/6] .env file already exists ✓"
fi

# ─── Step 6: Start all services with production profile ─────────
echo "[6/6] Starting all services (with deploy agent)..."
cd "$INSTALL_DIR"
docker compose --profile production up -d --build

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✅ Setup complete!"
echo ""
echo "  Services running:"
echo "    • Backend:      http://localhost:9090"
echo "    • Admin Panel:  http://localhost:80"
echo "    • SMPP:         localhost:2775"
echo "    • Synapse:      http://localhost:8008"
echo "    • Element:      http://localhost:8080"
echo "    • Deploy Agent: http://localhost:9000"
echo ""
echo "  Webhook URL for GitHub:"
echo "    http://YOUR_SERVER_IP:9000/webhook"
echo ""
echo "  Check deploy status:"
echo "    curl http://localhost:9000/status"
echo ""
echo "═══════════════════════════════════════════════════════════"
