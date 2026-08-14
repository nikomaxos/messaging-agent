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
    sudo docker build --no-cache -t "$image_name" "$path" 2>&1 | tee -a "$LOG_FILE"
    
    log "Importing $image_name into k3s local..."
    sudo docker save "$image_name" > "/tmp/${image_name}.tar"
    sudo k3s ctr images import "/tmp/${image_name}.tar" 2>&1 | tee -a "$LOG_FILE"
    
    for node in 10.10.10.194 10.10.10.195; do
        log "Syncing $image_name to worker $node..."
        scp -o StrictHostKeyChecking=no "/tmp/${image_name}.tar" "ubuntu@${node}:/tmp/" 2>&1 | tee -a "$LOG_FILE"
        ssh -o StrictHostKeyChecking=no "ubuntu@${node}" "sudo k3s ctr images import /tmp/${image_name}.tar && rm /tmp/${image_name}.tar" 2>&1 | tee -a "$LOG_FILE"
    done
    rm "/tmp/${image_name}.tar"
done

# Step 3: Apply Manifests
log "--- Step 3: Applying Kubernetes Manifests ---"
sudo kubectl delete endpoints postgres redis kafka --ignore-not-found 2>&1 | tee -a "$LOG_FILE"
sudo kubectl apply -f k8s-manifests/ 2>&1 | tee -a "$LOG_FILE"

# Step 4: Wait for StatefulSets to be ready
log "--- Step 4: Waiting for StatefulSets (Postgres, Matrix) to be ready ---"
sudo kubectl rollout status statefulset ma-postgres --timeout=120s 2>&1 | tee -a "$LOG_FILE" || true
sudo kubectl rollout status statefulset ma-synapse --timeout=120s 2>&1 | tee -a "$LOG_FILE" || true

log "--- Step 4.1: Migrating Legacy Postgres Data to K8s ---"
# Check if the DB is already populated to avoid duplicate restores
DB_CHECK=$(sudo kubectl exec -i ma-postgres-0 -- psql -U msgagent -d messagingagent -c "\dt" || echo "empty")
if [[ "$DB_CHECK" == *"empty"* || "$DB_CHECK" == *"Did not find any relations"* ]]; then
    log "Database is empty. Starting pg_dump from legacy VM (10.10.10.192)..."
    sudo kubectl exec -i ma-postgres-0 -- bash -c "PGPASSWORD=msgagent pg_dump -h 10.10.10.192 -U msgagent -d messagingagent" > /tmp/legacy_dump.sql || log "pg_dump failed"
    if [ -f /tmp/legacy_dump.sql ]; then
        log "Restoring SQL dump to ma-postgres-0..."
        cat /tmp/legacy_dump.sql | sudo kubectl exec -i ma-postgres-0 -- bash -c "PGPASSWORD=msgagent psql -U msgagent -d messagingagent" || log "psql restore failed"
        rm /tmp/legacy_dump.sql
        log "Postgres Migration Complete."
    fi
else
    log "Database already populated. Skipping Postgres migration."
fi

log "--- Step 4.2: Migrating Legacy Matrix Media to K8s ---"
# Create a temp folder and rsync from legacy VM
mkdir -p /tmp/synapse-migration
log "Copying Matrix data from legacy VM..."
scp -r -o StrictHostKeyChecking=no ubuntu@10.10.10.192:/opt/matrix/synapse/data/* /tmp/synapse-migration/ 2>/dev/null || log "scp failed or no matrix data"
# Copy into the new pod
if [ -d "/tmp/synapse-migration" ] && [ "$(ls -A /tmp/synapse-migration)" ]; then
    log "Transferring Matrix data to ma-synapse-0..."
    # Using tar to stream files into kubectl exec is much faster and reliable than kubectl cp for many files
    cd /tmp/synapse-migration && tar cf - . | sudo kubectl exec -i ma-synapse-0 -- tar xf - -C /data
    cd -
    rm -rf /tmp/synapse-migration
    log "Restarting Synapse to pick up config..."
    sudo kubectl rollout restart statefulset ma-synapse
    log "Matrix Migration Complete."
else
    log "No Matrix data found to migrate."
fi

# Step 5: Force Rollout Restart for all deployments
log "--- Step 5: Forcing Rollout Restarts ---"
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
