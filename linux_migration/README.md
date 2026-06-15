# Windows to Linux Migration Guide

This folder contains all the necessary scripts and context to seamlessly migrate the Messaging Agent project from your current Windows environment to a new Linux PC.

## Step 1: Backup Data on Windows

Before moving, you need to export the state of your databases and configs.
1. Open PowerShell as Administrator on your Windows machine.
2. Navigate to this `linux_migration` directory.
3. Run the backup script:
   ```powershell
   .\backup_windows.ps1
   ```
4. This will create a `migration_backup_YYYYMMDD_HHMMSS.zip` file in the root of the project.

## Step 2: Transfer to Linux PC

1. Move the entire `messaging-agent` project folder (including the newly generated `.zip` file) to your new Linux PC. You can also push the code to a private GitHub repository, clone it on the Linux PC, and just transfer the `.zip` backup file manually.

## Step 3: Install & Deploy on Linux

1. On the new Linux PC, navigate to the `linux_migration` folder within the project.
2. Make the deployment script executable and run it:
   ```bash
   chmod +x deploy_linux.sh
   ./deploy_linux.sh
   ```
   *This script will install Java, Node, Maven, Docker, and Android build tools. It will also spin up the docker containers.*

## Step 4: Restore Data on Linux

1. Unzip your backup file:
   ```bash
   unzip migration_backup_*.zip -d ./migration_backup
   ```
2. Restore the PostgreSQL database:
   ```bash
   docker exec -i ma-postgres psql -U msgagent -d messagingagent < ./migration_backup/messagingagent_dump.sql
   ```
3. Restore Matrix configurations (if applicable):
   ```bash
   cp -r ./migration_backup/matrix/* ../matrix/
   ```
4. Restore ADB Keys:
   ```bash
   mkdir -p ~/.android
   cp ./migration_backup/adbkey* ~/.android/
   ```

## Step 5: Resume AI Collaboration

1. Open your code editor (like VS Code) on the Linux PC.
2. Start an Antigravity AI session.
3. Provide the AI with the `init_prompt.md` file found in this directory. 
   *(e.g., "Read linux_migration/init_prompt.md and tell me when you are ready to continue where we left off.")*
4. The AI will instantly regain full context of the architecture, recent bugs resolved, and the state of the project.
