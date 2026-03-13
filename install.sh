#!/bin/bash
# Messaging Agent Installer for Linux and macOS

set -e

# Colors for terminal output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${GREEN}===============================================${NC}"
echo -e "${GREEN}      Messaging Agent Installation Script      ${NC}"
echo -e "${GREEN}===============================================${NC}"

# Check for required commands
echo -e "\n${YELLOW}Checking prerequisites...${NC}"

command -v docker >/dev/null 2>&1 || { echo -e "${RED}Error: docker is not installed. Please install Docker first.${NC}" >&2; exit 1; }
echo -e "${GREEN}✓ Docker is installed${NC}"

command -v docker-compose >/dev/null 2>&1 || command -v docker compose >/dev/null 2>&1 || { echo -e "${RED}Error: docker compose is not installed. Please install Docker Compose first.${NC}" >&2; exit 1; }
echo -e "${GREEN}✓ Docker Compose is installed${NC}"

# Start Docker containers
echo -e "\n${YELLOW}Starting Messaging Agent services...${NC}"
if docker compose version > /dev/null 2>&1; then
    docker compose up -d --build
else
    docker-compose up -d --build
fi

echo -e "\n${GREEN}===============================================${NC}"
echo -e "${GREEN}Installation Complete!${NC}"
echo -e "${GREEN}===============================================${NC}"
echo -e "The Messaging Agent stack is now running in the background."
echo -e ""
echo -e "Access the services at:"
echo -e "- Admin Panel: http://localhost (or http://127.0.0.1)"
echo -e "  - Default username: admin"
echo -e "  - Default password: changeme"
echo -e "- SMPP Server: localhost:2775"
echo -e ""
echo -e "To view logs, run: docker compose logs -f"
echo -e "To stop the services, run: docker compose down"
echo -e "==============================================="
