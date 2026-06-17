#!/bin/bash
docker compose stop backend
docker exec -e PGPASSWORD=msgagent -i ma-postgres psql -U msgagent -d postgres -c 'DROP DATABASE messagingagent WITH (FORCE);'
docker exec -e PGPASSWORD=msgagent -i ma-postgres psql -U msgagent -d postgres -c 'CREATE DATABASE messagingagent;'
docker exec -e PGPASSWORD=msgagent -i ma-postgres psql -U msgagent -d messagingagent < ./linux_migration/migration_backup/messagingagent_dump.sql
docker compose start backend
