#!/bin/bash
# Backup script for messagingagent database
# This script creates a pg_dump of the database and saves it in the backups/ directory.

set -e

BACKUP_DIR="./backups"
mkdir -p "$BACKUP_DIR"

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="$BACKUP_DIR/messagingagent_backup_$TIMESTAMP.sql"

echo "Creating database backup: $BACKUP_FILE..."
docker exec ma-postgres pg_dump -U msgagent -d messagingagent -F c -f "/tmp/backup.dump"
docker cp ma-postgres:/tmp/backup.dump "$BACKUP_FILE"
docker exec ma-postgres rm /tmp/backup.dump

echo "Backup completed successfully: $BACKUP_FILE"
