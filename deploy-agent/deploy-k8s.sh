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

cd "$REPO_DIR"
git fetch origin "$DEPLOY_BRANCH" >/dev/null 2>&1 || true
COMMIT_SHA=$(git rev-parse --short HEAD || echo "unknown")

if ! docker buildx version >/dev/null 2>&1; then
    log "Installing docker-buildx to fix legacy builder warnings..."
    sudo apt-get update >/dev/null 2>&1
    sudo apt-get install -y docker-buildx >/dev/null 2>&1
fi

declare -A SERVICES
SERVICES=(
  ["core-service"]="services/core-service"
  ["routing-engine"]="services/routing-engine"
  ["smpp-edge"]="services/smpp-edge"
  ["rcs-mautrix"]="services/rcs-mautrix"
  ["api-gateway"]="services/api-gateway"
  ["prefix-updater"]="services/prefix-updater"
  ["ai-service"]="services/ai-service"
  ["admin-panel"]="admin-panel"
)

declare -A BUILD_LIST
if [ -f "${REPO_DIR}/.last-deploy" ]; then
    PREV_COMMIT=$(cat "${REPO_DIR}/.last-deploy")
    log "Finding changed files since $PREV_COMMIT..."
    if CHANGED_FILES=$(git diff --name-only $PREV_COMMIT origin/$DEPLOY_BRANCH 2>/dev/null); then
        log "Changed files:"
        echo "$CHANGED_FILES" | tee -a "$LOG_FILE"
        if echo "$CHANGED_FILES" | grep -qE "^shared-libs/|^pom.xml$"; then
            log "Shared dependencies changed. Building all services."
            for service in "${!SERVICES[@]}"; do BUILD_LIST["$service"]="${SERVICES[$service]}"; done
        else
            for service in "${!SERVICES[@]}"; do
                path="${SERVICES[$service]}"
                if echo "$CHANGED_FILES" | grep -q "^${path}/"; then
                    BUILD_LIST["$service"]="$path"
                fi
            done
        fi
    else
        log "Could not calculate diff from $PREV_COMMIT. Building all services."
        for service in "${!SERVICES[@]}"; do BUILD_LIST["$service"]="${SERVICES[$service]}"; done
    fi
else
    log "No previous deployment found. Building all services."
    for service in "${!SERVICES[@]}"; do BUILD_LIST["$service"]="${SERVICES[$service]}"; done
fi

NUM_SERVICES=${#BUILD_LIST[@]}
if [ $NUM_SERVICES -eq 0 ]; then
    TOTAL_STEPS=8
else
    TOTAL_STEPS=$(( 8 + NUM_SERVICES * 3 ))
fi

log "[TOTAL_STEPS] $TOTAL_STEPS"
log "[STEP_DEF] 1|Fetch Latest Code|Syncs the target node with the latest committed code branch from GitHub."

STEP=2
if [ $NUM_SERVICES -eq 0 ]; then
    log "[STEP_DEF] $STEP|Build & Distribute Images|No services changed, skipping."
    STEP=$((STEP+1))
else
    for service in "${!BUILD_LIST[@]}"; do
        log "[STEP_DEF] $STEP|Build $service|Compiling Docker container for $service"
        STEP=$((STEP+1))
        for node in 10.10.10.194 10.10.10.195; do
            node_id=$(echo $node | awk -F. '{print $4}')
            log "[STEP_DEF] $STEP|Sync $service to worker $node_id|Transferring Docker image to worker node"
            STEP=$((STEP+1))
        done
    done
fi

log "[STEP_DEF] $STEP|Apply Kubernetes Configs|Updates the cluster configurations to reflect the latest networking and deployment manifests."
STEP=$((STEP+1))
log "[STEP_DEF] $STEP|Initialize Databases|Ensures the stateful persistence layer is fully booted and ready."
STEP=$((STEP+1))
log "[STEP_DEF] $STEP|Trigger Rolling Updates|Instructs the cluster to gracefully cycle pods and transition traffic."
STEP=$((STEP+1))
log "[STEP_DEF] $STEP|Verify Pod Health|Monitors the deployment rollout status until all replacement pods pass."
STEP=$((STEP+1))
log "[STEP_DEF] $STEP|Scan Host Systems|Queries the underlying Ubuntu nodes for pending security patches or system package updates."
STEP=$((STEP+1))
log "[STEP_DEF] $STEP|Analyze Cluster Stability|Performs a deep diagnostic sweep across all namespaces to detect any crashing or evicting pods."
STEP=$((STEP+1))
log "[STEP_DEF] $STEP|Prune Stale Resources|Executes garbage collection to free disk space by deleting previous Docker layers and orphaned images."


CURRENT_STEP=1
log "--- Step $CURRENT_STEP: Git fetch & reset ---"
git fetch origin "$DEPLOY_BRANCH" 2>&1 | tee -a "$LOG_FILE" || true
git reset --hard "origin/$DEPLOY_BRANCH" 2>&1 | tee -a "$LOG_FILE" || true
COMMIT_SHA=$(git rev-parse --short HEAD)
log "Now at commit: $COMMIT_SHA"
CURRENT_STEP=$((CURRENT_STEP+1))

if [ ${#BUILD_LIST[@]} -eq 0 ]; then
    log "--- Step $CURRENT_STEP: Build & Distribute Images ---"
    log "No service changes detected. Skipping Docker build phase."
    CURRENT_STEP=$((CURRENT_STEP+1))
else
    for service in "${!BUILD_LIST[@]}"; do
        path="${BUILD_LIST[$service]}"
        image_name="messaging-agent-${service}:latest"
        log "--- Step $CURRENT_STEP: Build $service ---"
        log "Building $image_name from $path..."
        sudo docker buildx build --load -t "$image_name" "$path" 2>&1 | tee -a "$LOG_FILE"
        
        log "Importing $image_name into k3s local..."
        sudo docker save "$image_name" > "/tmp/${image_name}.tar"
        sudo k3s ctr images import "/tmp/${image_name}.tar" 2>&1 | tee -a "$LOG_FILE"
        CURRENT_STEP=$((CURRENT_STEP+1))
        
        for node in 10.10.10.194 10.10.10.195; do
            node_id=$(echo $node | awk -F. '{print $4}')
            log "--- Step $CURRENT_STEP: Sync $service to worker $node_id ---"
            log "Syncing $image_name to worker $node..."
            scp -o StrictHostKeyChecking=no "/tmp/${image_name}.tar" "ubuntu@${node}:/tmp/" 2>&1 | tee -a "$LOG_FILE"
            ssh -o StrictHostKeyChecking=no "ubuntu@${node}" "sudo k3s ctr images import /tmp/${image_name}.tar && rm /tmp/${image_name}.tar" 2>&1 | tee -a "$LOG_FILE"
            CURRENT_STEP=$((CURRENT_STEP+1))
        done
        rm "/tmp/${image_name}.tar"
    done
fi

log "--- Step $CURRENT_STEP: Apply Kubernetes Configs ---"
sudo kubectl delete endpoints postgres redis kafka --ignore-not-found 2>&1 | tee -a "$LOG_FILE"
sudo kubectl apply -f k8s-manifests/ 2>&1 | tee -a "$LOG_FILE"
CURRENT_STEP=$((CURRENT_STEP+1))

log "--- Step $CURRENT_STEP: Initialize Databases ---"
sudo kubectl rollout status statefulset ma-postgres --timeout=120s 2>&1 | tee -a "$LOG_FILE" || true
sudo kubectl rollout status statefulset ma-synapse --timeout=120s 2>&1 | tee -a "$LOG_FILE" || true
CURRENT_STEP=$((CURRENT_STEP+1))

log "--- Step $CURRENT_STEP: Trigger Rolling Updates ---"
if [ ${#BUILD_LIST[@]} -eq 0 ]; then
    log "No images built, skipping forced rollout restarts."
else
    for service in "${!BUILD_LIST[@]}"; do
        dep="ma-${service}"
        if sudo kubectl get deployment "$dep" >/dev/null 2>&1; then
            log "Restarting deployment $dep..."
            sudo kubectl rollout restart deployment "$dep" 2>&1 | tee -a "$LOG_FILE"
        fi
    done
fi
CURRENT_STEP=$((CURRENT_STEP+1))

log "--- Step $CURRENT_STEP: Verify Pod Health ---"
if [ ${#BUILD_LIST[@]} -eq 0 ]; then
    log "No rollouts to wait for."
else
    for service in "${!BUILD_LIST[@]}"; do
        dep="ma-${service}"
        if sudo kubectl get deployment "$dep" >/dev/null 2>&1; then
            log "Waiting for deployment $dep..."
            sudo kubectl rollout status deployment "$dep" --timeout=90s 2>&1 | tee -a "$LOG_FILE"
        fi
    done
fi
CURRENT_STEP=$((CURRENT_STEP+1))

log "--- Step $CURRENT_STEP: Scan Host Systems ---"
for node in 10.10.10.193 10.10.10.194 10.10.10.195; do
    log "Checking updates on $node..."
    UPGRADABLE=$(ssh -o StrictHostKeyChecking=no "ubuntu@$node" "sudo apt-get update >/dev/null 2>&1 && sudo apt-get -s upgrade | grep -P '^\d+ upgraded'" || true)
    if [ ! -z "$UPGRADABLE" ]; then
        NUM_UPGRADES=$(echo "$UPGRADABLE" | awk '{print $1}')
        if [ "$NUM_UPGRADES" != "0" ]; then
            PKG_LIST=$(ssh -o StrictHostKeyChecking=no "ubuntu@$node" "sudo apt-get -s upgrade | grep '^Inst' | awk '{print \$2}' | tr '\n' ' '")
            log "[VM_UPDATE_NEEDED] Node $node: $NUM_UPGRADES packages can be upgraded: $PKG_LIST"
        fi
    fi
done
CURRENT_STEP=$((CURRENT_STEP+1))

log "--- Step $CURRENT_STEP: Analyze Cluster Stability ---"
FAILED_PODS=$(sudo kubectl get pods -A | grep -E 'CrashLoopBackOff|Error|ImagePullBackOff|Evicted|OOMKilled' || true)
if [ ! -z "$FAILED_PODS" ]; then
    echo "$FAILED_PODS" | while read -r line; do
        namespace=$(echo "$line" | awk '{print $1}')
        pod=$(echo "$line" | awk '{print $2}')
        state=$(echo "$line" | awk '{print $4}')
        log "[CONTAINER_ERROR] Namespace: $namespace, Pod: $pod, State: $state"
    done
else
    log "[CONTAINER_HEALTH_OK] All pods are running normally."
fi
CURRENT_STEP=$((CURRENT_STEP+1))

log "--- Step $CURRENT_STEP: Prune Stale Resources ---"
sudo docker container prune -f >/dev/null 2>&1
sudo docker image prune -a -f --filter "until=168h" >/dev/null 2>&1
sudo docker builder prune -f --filter "until=168h" >/dev/null 2>&1
for node in 10.10.10.193 10.10.10.194 10.10.10.195; do
    log "Pruning k3s images on $node..."
    ssh -o StrictHostKeyChecking=no "ubuntu@$node" "sudo k3s crictl rmi --prune" 2>&1 | tee -a "$LOG_FILE" || true
done

log "========== DEPLOY SUCCEEDED =========="
log "Commit: $COMMIT_SHA"
echo "$COMMIT_SHA" > "${REPO_DIR}/.last-deploy"
exit 0
