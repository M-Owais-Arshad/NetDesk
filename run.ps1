# ============================================================
# NetDesk - Zero-Config Smart Launcher & Runtime Manager
# ============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "NetDesk - Remote Desktop & Collaboration Suite"

Clear-Host
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "      NETDESK - Remote Desktop & Collaboration Station       " -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Determine workspace directory
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path -ErrorAction SilentlyContinue
if ($scriptDir) { Set-Location $scriptDir }

# If invoked via remote `irm ... | iex` in an empty folder, auto-download source files
if (-not (Test-Path "ServerApp.java") -or -not (Test-Path "ClientApp.java")) {
    Write-Host "[i] Downloading NetDesk files from GitHub repository..." -ForegroundColor Cyan
    $dest = Join-Path (Get-Location).Path "NetDesk"
    if (-not (Test-Path $dest)) {
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
    }
    Set-Location $dest
    if (-not (Test-Path "ServerApp.java")) {
        Invoke-WebRequest -Uri "https://raw.githubusercontent.com/M-Owais-Arshad/NetDesk/main/ServerApp.java" -OutFile "ServerApp.java"
        Invoke-WebRequest -Uri "https://raw.githubusercontent.com/M-Owais-Arshad/NetDesk/main/ClientApp.java" -OutFile "ClientApp.java"
    }
}

# Step 1: Check Java Environment
Write-Host "[1/2] Verifying Java Environment..." -ForegroundColor White
$javaOk = $false
try {
    $ver = java -version 2>&1
    if ($LASTEXITCODE -eq 0 -or $ver -match "version") {
        $javaOk = $true
    }
} catch {
    $javaOk = $false
}

if (-not $javaOk) {
    Write-Host "[!] Java runtime not found on this machine." -ForegroundColor Yellow
    Write-Host "[i] Automatically installing OpenJDK via winget..." -ForegroundColor Cyan
    try {
        winget install Microsoft.OpenJDK.17 --accept-package-agreements --accept-source-agreements --silent
        $env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
    } catch {
        Write-Host "[X] Automatic installation failed. Please install Java (JDK 8+) from https://adoptium.net" -ForegroundColor Red
        Write-Host "Press any key to exit..." -ForegroundColor Gray
        $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
        exit 1
    }
}

Write-Host "[✓] Java Environment Ready." -ForegroundColor Green
Write-Host ""

# Background Compilation Function
function Ensure-NetDeskCompiled {
    $needsCompile = $false
    if (-not (Test-Path "ServerApp.class") -or -not (Test-Path "ClientApp.class")) {
        $needsCompile = $true
    } else {
        $serverSrc = (Get-Item "ServerApp.java").LastWriteTime
        $serverCls = (Get-Item "ServerApp.class").LastWriteTime
        $clientSrc = (Get-Item "ClientApp.java").LastWriteTime
        $clientCls = (Get-Item "ClientApp.class").LastWriteTime
        if ($serverSrc -gt $serverCls -or $clientSrc -gt $clientCls) {
            $needsCompile = $true
        }
    }

    if ($needsCompile) {
        Write-Host "[i] Compiling NetDesk components in background..." -ForegroundColor Cyan
        javac ServerApp.java ClientApp.java
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[X] Compilation failed. Please verify source files." -ForegroundColor Red
            Write-Host "Press any key to exit..." -ForegroundColor Gray
            $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
            exit 1
        }
        Write-Host "[✓] Components compiled successfully." -ForegroundColor Green
    }
}

# Step 2: Interactive Menu
while ($true) {
    Write-Host "Select an option to launch:" -ForegroundColor White
    Write-Host "  [1] Launch SERVER  (Host Machine / Control Screen)" -ForegroundColor Yellow
    Write-Host "  [2] Launch CLIENT  (Share Screen & Accept Remote Control)" -ForegroundColor Green
    Write-Host "  [3] Exit" -ForegroundColor Gray
    Write-Host ""
    $choice = Read-Host "Enter your choice [1-3]"

    switch ($choice) {
        "1" {
            Ensure-NetDeskCompiled
            Write-Host ""
            Write-Host "Launching Server Station..." -ForegroundColor Yellow
            Write-Host "👉 Note the Server IP on screen and share it with the Client." -ForegroundColor Cyan
            Write-Host ""
            Start-Process -FilePath "java" -ArgumentList "ServerApp"
            exit 0
        }
        "2" {
            Ensure-NetDeskCompiled
            Write-Host ""
            Write-Host "Launching Client Station..." -ForegroundColor Green
            Write-Host "👉 Enter the Server's IP address and click 'Connect'." -ForegroundColor Cyan
            Write-Host ""
            Start-Process -FilePath "java" -ArgumentList "ClientApp"
            exit 0
        }
        "3" {
            Write-Host "Exiting NetDesk..." -ForegroundColor Gray
            exit 0
        }
        default {
            Write-Host "Invalid choice. Please enter 1, 2, or 3." -ForegroundColor Red
            Write-Host ""
        }
    }
}
