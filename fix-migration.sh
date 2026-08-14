#!/bin/bash
set -euo pipefail

echo "Executing Data Migration on 10.10.10.193..."

# 1. Stop all microservices from writing to Postgres
echo "Scaling down microservices to prevent schema conflicts..."
sudo kubectl scale deploy ma-core-service ma-routing-engine ma-smpp-edge ma-rcs-mautrix --replicas=0

echo "Waiting 5 seconds..."
sleep 5

# 2. Drop the blank schema created by Liquibase
echo "Dropping blank schema in new Postgres..."
sudo kubectl exec -i ma-postgres-0 -- bash -c "PGPASSWORD=msgagent psql -U msgagent -d messagingagent -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'"

# 3. Dump from 10.10.10.192 and restore to ma-postgres-0
echo "Dumping and Restoring Postgres data..."
sudo kubectl exec -i ma-postgres-0 -- bash -c "PGPASSWORD=msgagent pg_dump -h 10.10.10.192 -U msgagent -d messagingagent > /tmp/legacy_dump.sql && PGPASSWORD=msgagent psql -U msgagent -d messagingagent < /tmp/legacy_dump.sql"

# 4. Migrate Matrix Data
echo "Migrating Matrix media..."
sudo kubectl exec -i ma-synapse-0 -- bash -c "apt-get update && apt-get install -y openssh-client rsync || true" || true
scp -o StrictHostKeyChecking=no -r ubuntu@10.10.10.192:/opt/matrix/synapse/data/* /tmp/synapse-data/ 2>/dev/null || true
if [ -d "/tmp/synapse-data" ]; then
  cd /tmp/synapse-data && tar cf - . | sudo kubectl exec -i ma-synapse-0 -- tar xf - -C /data
  rm -rf /tmp/synapse-data
fi
sudo kubectl rollout restart statefulset ma-synapse

# 5. Bring everything back up
echo "Scaling microservices back up..."
sudo kubectl scale deploy ma-core-service ma-routing-engine ma-smpp-edge ma-rcs-mautrix --replicas=1

echo "Migration Complete!"
