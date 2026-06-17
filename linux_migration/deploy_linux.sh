#!/bin/bash
set -e

echo "================================================="
echo "   Messaging Agent - Linux Migration & Deploy    "
echo "================================================="

# 1. Update and install basic dependencies
echo ">>> Updating system packages and installing basic tools..."
sudo apt-get update -y
sudo apt-get install -y curl wget git unzip zip tar build-essential software-properties-common jq

# 2. Install Java 21 (OpenJDK)
echo ">>> Installing OpenJDK 21..."
sudo apt-get install -y openjdk-21-jdk maven
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# 3. Install Node.js (via NodeSource)
echo ">>> Installing Node.js (v20)..."
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
sudo npm install -g pnpm yarn typescript vite ts-node

# 4. Install Docker & Docker Compose
echo ">>> Installing Docker..."
if ! command -v docker &> /dev/null; then
    curl -fsSL https://get.docker.com -o get-docker.sh
    sudo sh get-docker.sh
    sudo usermod -aG docker $USER
    rm get-docker.sh
    echo "Docker installed. You may need to log out and log back in for group changes to take effect."
else
    echo "Docker is already installed."
fi

# 5. Install Android SDK Command Line Tools
echo ">>> Installing Android Command Line Tools..."
mkdir -p $HOME/Android/Sdk/cmdline-tools
cd $HOME/Android/Sdk/cmdline-tools
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
unzip -q cmdline-tools.zip
mv cmdline-tools latest
rm cmdline-tools.zip
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 6. Initialize App / Docker Compose
echo ">>> Setting up environment..."
cd "$(dirname "$0")/.." # Go to project root

# Check if docker-compose.yml exists, if so try to start infrastructure
if [ -f "docker-compose.yml" ]; then
    echo ">>> Pulling docker images and starting backend infrastructure (Postgres, Redis, Kafka)..."
    sudo docker compose pull
    sudo docker compose up -d
fi

# 7. Restore Backup Data Automatically
echo ">>> Checking for migration backups..."
cd linux_migration
BACKUP_ZIP=$(ls migration_backup_*.zip 2>/dev/null | head -n 1)

if [ -n "$BACKUP_ZIP" ]; then
    echo ">>> Found backup file: $BACKUP_ZIP"
    echo ">>> Extracting and restoring data..."
    
    # Unzip backup safely
    mkdir -p ./migration_backup
    unzip -qo $BACKUP_ZIP -d ./migration_backup
    
    # Wait for Postgres to become healthy before restoring
    echo "    Waiting for Postgres to initialize..."
    sleep 10
    until sudo docker exec ma-postgres pg_isready -U msgagent -d messagingagent; do
        sleep 2
    done
    
    echo "    Restoring Postgres Database..."
    sudo docker exec -i ma-postgres psql -U msgagent -d messagingagent < ./migration_backup/messagingagent_dump.sql
    
    echo "    Restoring Matrix Configs..."
    cp -rf ./migration_backup/matrix/* ../matrix/ 2>/dev/null || true
    
    echo "    Restoring ADB Keys..."
    mkdir -p ~/.android
    cp -f ./migration_backup/adbkey* ~/.android/ 2>/dev/null || true
    
    echo ">>> Data successfully restored!"
else
    echo ">>> No backup zip found in linux_migration directory. Skipping restore."
fi

# 8. Create Antigravity specific directories
echo ">>> Ensuring Antigravity AI structure exists..."
mkdir -p ~/.gemini/antigravity/knowledge

echo "================================================="
echo " Installation & Deploy Complete! "
echo " Next Steps:"
echo " 1. Feed 'linux_migration/init_prompt.md' to Antigravity."
echo " 2. If Docker fails due to permissions, log out and log back in."
echo "================================================="
