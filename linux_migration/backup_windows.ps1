<#
.SYNOPSIS
Backs up the Messaging Agent configuration and databases from Windows.

.DESCRIPTION
This script will:
1. Export the PostgreSQL database running in the 'ma-postgres' Docker container.
2. Archive the 'matrix' directory (which contains Synapse/Mautrix config).
3. Copy any .env files or special configurations.
4. Package everything into a single zip file for easy transfer to the Linux PC.
#>

$ErrorActionPreference = "Stop"

Write-Host "================================================="
Write-Host "   Messaging Agent - Windows Backup Script       "
Write-Host "================================================="

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
# Adjust if script is in linux_migration folder
if ((Split-Path $ProjectRoot -Leaf) -eq "linux_migration") {
    $ProjectRoot = Split-Path -Parent $ProjectRoot
}

$BackupDir = Join-Path $ProjectRoot "migration_backup_$(Get-Date -Format 'yyyyMMdd_HHmmss')"
New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null

Write-Host ">>> Creating backup directory: $BackupDir"

# 1. Backup PostgreSQL
Write-Host ">>> Backing up PostgreSQL database..."
$DbDumpPath = Join-Path $BackupDir "messagingagent_dump.sql"

# Check if Docker is running
$dockerInfo = docker info 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "    [WARNING] Docker is not running. Skipping PostgreSQL database backup." -ForegroundColor Yellow
} else {
    # Dump DB using pg_dump inside the container
    docker exec -t ma-postgres pg_dump -U msgagent -d messagingagent -F p -f /tmp/messagingagent_dump.sql
    if ($LASTEXITCODE -eq 0) {
        docker cp "ma-postgres:/tmp/messagingagent_dump.sql" $DbDumpPath
        Write-Host "    [OK] PostgreSQL database dumped successfully."
    } else {
        Write-Host "    [WARNING] Could not dump PostgreSQL database. Is the ma-postgres container running?" -ForegroundColor Yellow
    }
}

# 2. Backup Matrix Configs
Write-Host ">>> Backing up Matrix configurations..."
$MatrixSource = Join-Path $ProjectRoot "matrix"
if (Test-Path $MatrixSource) {
    $MatrixBackup = Join-Path $BackupDir "matrix"
    Copy-Item -Path $MatrixSource -Destination $MatrixBackup -Recurse -Force
    Write-Host "    [OK] Matrix configurations copied."
} else {
    Write-Host "    [INFO] No Matrix configuration folder found."
}

# 3. Backup Environments & ADB Keys
Write-Host ">>> Backing up environment files..."
Get-ChildItem -Path $ProjectRoot -Filter ".env*" | ForEach-Object {
    Copy-Item $_.FullName -Destination $BackupDir -Force
    Write-Host "    [OK] Copied $($_.Name)"
}

$AdbKey = Join-Path $env:USERPROFILE ".android\adbkey"
if (Test-Path $AdbKey) {
    $AdbKeyBackup = Join-Path $BackupDir "adbkey"
    $AdbKeyPubBackup = Join-Path $BackupDir "adbkey.pub"
    Copy-Item $AdbKey -Destination $AdbKeyBackup -Force
    Copy-Item "$AdbKey.pub" -Destination $AdbKeyPubBackup -Force
    Write-Host "    [OK] Copied ADB keys."
}

# 4. Zip the backup
Write-Host ">>> Zipping backup..."
$ZipPath = Join-Path $ProjectRoot "$((Get-Item $BackupDir).Name).zip"
Compress-Archive -Path "$BackupDir\*" -DestinationPath $ZipPath -Force

Write-Host ">>> Cleaning up temporary backup folder..."
Remove-Item -Path $BackupDir -Recurse -Force

Write-Host "================================================="
Write-Host " Backup Complete! "
Write-Host " File created: $ZipPath"
Write-Host " Please transfer this zip file to your Linux PC."
Write-Host "================================================="
