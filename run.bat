@echo off
title Zeta-NetDesk - Remote Desktop ^& Collaboration Station
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1"
if %ERRORLEVEL% neq 0 (
    echo.
    echo ============================================================
    echo [NetDesk Launcher stopped with status code %ERRORLEVEL%]
    echo ============================================================
    pause
)
