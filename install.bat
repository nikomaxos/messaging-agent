@echo off
setlocal enabledelayedexpansion
title Messaging Agent Installer

echo ===============================================
echo       Messaging Agent Installation Script      
echo ===============================================
echo.

:: Check prerequisites
echo Checking prerequisites...
echo.

:: Check Docker
where docker >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Docker is not installed or not in PATH.
    echo Please install Docker Desktop for Windows first: https://www.docker.com/products/docker-desktop
    pause
    exit /b 1
)
echo [OK] Docker is installed

:: Check Docker Compose
where docker-compose >nul 2>nul
set HAS_DOCKER_COMPOSE=%errorlevel%

docker compose version >nul 2>nul
set HAS_DOCKER_COMPOSE_PLUGIN=%errorlevel%

if %HAS_DOCKER_COMPOSE% neq 0 if %HAS_DOCKER_COMPOSE_PLUGIN% neq 0 (
    echo [ERROR] Docker Compose is not installed.
    echo Please install Docker Compose first.
    pause
    exit /b 1
)
echo [OK] Docker Compose is installed

echo.
echo Starting Messaging Agent services...
echo.

if %HAS_DOCKER_COMPOSE_PLUGIN% equ 0 (
    docker compose up -d --build
) else (
    docker-compose up -d --build
)

echo.
echo ===============================================
echo Installation Complete!
echo ===============================================
echo The Messaging Agent stack is now running in the background.
echo.
echo Access the services at:
echo - Admin Panel: http://localhost (or http://127.0.0.1)
echo   - Default username: admin
echo   - Default password: changeme
echo - SMPP Server: localhost:2775
echo.
echo To view logs, run: docker compose logs -f
echo To stop the services, run: docker compose down
echo ===============================================
pause
