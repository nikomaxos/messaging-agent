#!/bin/bash
set -euo pipefail

REPO_DIR="${REPO_DIR:-/repo}"
LOG_DIR="${REPO_DIR}/deploy-logs"
DEPLOY_BRANCH="${DEPLOY_BRANCH:-main}"
COMPOSE_SERVICES="${COMPOSE_SERVICES:-backend admin-panel}"

mkdir -p "$LOG_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="${LOG_DIR}/deploy_${TIMESTAMP}.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"
}

log "========== DEPLOY STARTED =========="
log "Branch: $DEPLOY_BRANCH"
log "Services: $COMPOSE_SERVICES"

# Step 1: Git pull
log "--- Step 1: Git fetch & reset ---"
cd "$REPO_DIR"
git fetch origin "$DEPLOY_BRANCH" 2>&1 | tee -a "$LOG_FILE"
git reset --hard "origin/$DEPLOY_BRANCH" 2>&1 | tee -a "$LOG_FILE"
COMMIT_SHA=$(git rev-parse --short HEAD)
log "Now at commit: $COMMIT_SHA"

# Step 2: Build
log "--- Step 2: Docker Compose build ---"
docker compose build $COMPOSE_SERVICES 2>&1 | tee -a "$LOG_FILE"

# Step 3: Rolling restart
log "--- Step 3: Rolling restart ---"
docker compose up -d --no-deps $COMPOSE_SERVICES 2>&1 | tee -a "$LOG_FILE"

# Step 4: Health check (wait up to 90s for backend)
log "--- Step 4: Health check ---"
HEALTH_OK=false
for i in $(seq 1 18); do
    sleep 5
    if docker compose exec -T backend curl -sf http://localhost:9090/actuator/health > /dev/null 2>&1; then
        HEALTH_OK=true
        log "Health check passed after $((i * 5))s"
        break
    fi
    log "Health check attempt $i/18 — waiting..."
done

if [ "$HEALTH_OK" = true ]; then
    log "========== DEPLOY SUCCEEDED =========="
    log "Commit: $COMMIT_SHA"
    echo "$COMMIT_SHA" > "${REPO_DIR}/.last-deploy"
    exit 0
else
    log "========== DEPLOY FAILED — HEALTH CHECK TIMEOUT =========="
    
    # Attempt rollback if we have a previous known-good commit
    if [ -f "${REPO_DIR}/.last-deploy" ]; then
        PREV_SHA=$(cat "${REPO_DIR}/.last-deploy")
        log "Rolling back to previous commit: $PREV_SHA"
        git reset --hard "$PREV_SHA" 2>&1 | tee -a "$LOG_FILE"
        docker compose build $COMPOSE_SERVICES 2>&1 | tee -a "$LOG_FILE"
        docker compose up -d --no-deps $COMPOSE_SERVICES 2>&1 | tee -a "$LOG_FILE"
        log "Rollback complete"
    fi
    exit 1
fi
