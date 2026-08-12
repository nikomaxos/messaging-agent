#!/bin/bash
set -euo pipefail

REPO_DIR="${REPO_DIR:-$HOME/messaging-agent}"
LOG_DIR="${REPO_DIR}/deploy-logs"
DEPLOY_BRANCH="${DEPLOY_BRANCH:-main}"

# Ensure repo directory exists
if [ ! -d "$REPO_DIR" ]; then
    echo "Cloning repository..."
    git clone https://github.com/nikomaxos/messaging-agent.git "$REPO_DIR"
fi

mkdir -p "$LOG_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="${LOG_DIR}/deploy_${TIMESTAMP}.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"
}

log "========== KUBERNETES DEPLOY STARTED =========="
log "Branch: $DEPLOY_BRANCH"

# Step 1: Git pull
log "--- Step 1: Git fetch & reset ---"
cd "$REPO_DIR"
git fetch origin "$DEPLOY_BRANCH" 2>&1 | tee -a "$LOG_FILE"
git reset --hard "origin/$DEPLOY_BRANCH" 2>&1 | tee -a "$LOG_FILE"
COMMIT_SHA=$(git rev-parse --short HEAD)
log "Now at commit: $COMMIT_SHA"

# Step 2: Apply Manifests
log "--- Step 2: Applying Kubernetes Manifests ---"
sudo kubectl apply -f k8s-manifests/ 2>&1 | tee -a "$LOG_FILE"

# Step 3: Wait for rollout
log "--- Step 3: Waiting for Rollouts ---"
DEPLOYMENTS=$(sudo kubectl get deployments -o jsonpath='{.items[*].metadata.name}')
for dep in $DEPLOYMENTS; do
    log "Waiting for deployment $dep..."
    sudo kubectl rollout status deployment "$dep" --timeout=90s 2>&1 | tee -a "$LOG_FILE"
done

log "========== DEPLOY SUCCEEDED =========="
log "Commit: $COMMIT_SHA"
echo "$COMMIT_SHA" > "${REPO_DIR}/.last-deploy"
exit 0
