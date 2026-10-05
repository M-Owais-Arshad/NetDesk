@echo off
title NetDesk - Remote Desktop ^& Collaboration Station
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1"
