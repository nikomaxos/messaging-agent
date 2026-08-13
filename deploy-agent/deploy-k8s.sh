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

# Step 2: Build and Import Images
log "--- Step 2: Building and Importing Docker Images ---"
cd "$REPO_DIR"

declare -A SERVICES
SERVICES=(
  ["core-service"]="services/core-service"
  ["routing-engine"]="services/routing-engine"
  ["smpp-edge"]="services/smpp-edge"
  ["rcs-mautrix"]="services/rcs-mautrix"
  ["api-gateway"]="services/api-gateway"
  ["prefix-updater"]="services/prefix-updater"
  ["admin-panel"]="admin-panel"
)

for service in "${!SERVICES[@]}"; do
    path="${SERVICES[$service]}"
    image_name="messaging-agent-${service}:latest"
    log "Building $image_name from $path..."
    sudo docker build -t "$image_name" "$path" 2>&1 | tee -a "$LOG_FILE"
    
    log "Importing $image_name into k3s..."
    sudo docker save "$image_name" | sudo k3s ctr images import - 2>&1 | tee -a "$LOG_FILE"
done

# Step 3: Apply Manifests
log "--- Step 3: Applying Kubernetes Manifests ---"
sudo kubectl apply -f k8s-manifests/ 2>&1 | tee -a "$LOG_FILE"

# Step 4: Force Rollout Restart for all deployments
log "--- Step 4: Forcing Rollout Restarts ---"
DEPLOYMENTS=$(sudo kubectl get deployments -o jsonpath='{.items[*].metadata.name}')
for dep in $DEPLOYMENTS; do
    log "Restarting deployment $dep..."
    sudo kubectl rollout restart deployment "$dep" 2>&1 | tee -a "$LOG_FILE"
done

# Step 5: Wait for rollout
log "--- Step 5: Waiting for Rollouts ---"
DEPLOYMENTS=$(sudo kubectl get deployments -o jsonpath='{.items[*].metadata.name}')
for dep in $DEPLOYMENTS; do
    log "Waiting for deployment $dep..."
    sudo kubectl rollout status deployment "$dep" --timeout=90s 2>&1 | tee -a "$LOG_FILE"
done

log "========== DEPLOY SUCCEEDED =========="
log "Commit: $COMMIT_SHA"
echo "$COMMIT_SHA" > "${REPO_DIR}/.last-deploy"
exit 0
