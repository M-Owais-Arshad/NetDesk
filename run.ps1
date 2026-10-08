# ============================================================
# Zeta-NetDesk - Zero-Config Smart Launcher & Runtime Manager
# ============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "Zeta-NetDesk - Remote Desktop & Collaboration Suite"

Clear-Host
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "    ZETA-NETDESK - Remote Desktop & Collaboration Station    " -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Determine workspace directory safely without Split-Path null binding error
$cmdPath = if ($MyInvocation -and $MyInvocation.MyCommand) { $MyInvocation.MyCommand.Path } else { $null }
if ($cmdPath -and (Test-Path $cmdPath)) {
    $scriptDir = Split-Path -Parent $cmdPath
    Set-Location $scriptDir
} else {
    # If invoked directly via `irm ... | iex`, store predictably in C:\Users\<Username>\Zeta-NetDesk
    $dest = Join-Path $env:USERPROFILE "Zeta-NetDesk"
    if (-not (Test-Path $dest)) {
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
    }
    Set-Location $dest
}

Write-Host "Working Directory: $((Get-Location).Path)" -ForegroundColor Gray
Write-Host ""

# Helper: Detect Javac compiler or standard JDK installations on Windows
function Get-JavacExecutable {
    if (Get-Command "javac" -ErrorAction SilentlyContinue) {
        return "javac"
    }
    $jdkSearchPaths = @(
        "$env:ProgramFiles\Java\jdk*\bin\javac.exe",
        "$env:ProgramFiles\Eclipse Adoptium\jdk*\bin\javac.exe",
        "$env:ProgramFiles\Microsoft\jdk*\bin\javac.exe",
        "$env:ProgramFiles\Zulu\zulu-*\bin\javac.exe",
        "$env:ProgramFiles\BellSoft\LibericaJDK*\bin\javac.exe",
        "${env:ProgramFiles(x86)}\Java\jdk*\bin\javac.exe"
    )
    foreach ($pat in $jdkSearchPaths) {
        $found = Get-Item $pat -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
        if ($found -and (Test-Path $found.FullName)) {
            $binDir = Split-Path -Parent $found.FullName
            $env:Path = "$binDir;$env:Path"
            return $found.FullName
        }
    }
    return $null
}

# Step 1: Automatic Update Synchronization on Launch
Write-Host "[1/3] Checking for latest Zeta-NetDesk updates..." -ForegroundColor Cyan
try {
    if (Test-Path ".git") {
        # If running inside a git clone, synchronize via git pull
        $gitRes = git pull --quiet 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[OK] Repository synchronized with GitHub (git pull)." -ForegroundColor Green
        }
    } else {
        # Standalone installation: fast fetch latest code from GitHub
        $rawBase = "https://raw.githubusercontent.com/M-Owais-Arshad/Zeta-NetDesk/main"
        Invoke-WebRequest -Uri "$rawBase/ServerApp.java" -OutFile "ServerApp.java.tmp" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        Invoke-WebRequest -Uri "$rawBase/ClientApp.java" -OutFile "ClientApp.java.tmp" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        Invoke-WebRequest -Uri "$rawBase/run.bat" -OutFile "run.bat.tmp" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        Invoke-WebRequest -Uri "$rawBase/run.ps1" -OutFile "run.ps1.tmp" -TimeoutSec 4 -UseBasicParsing -ErrorAction SilentlyContinue

        Move-Item -Path "ServerApp.java.tmp" -Destination "ServerApp.java" -Force
        Move-Item -Path "ClientApp.java.tmp" -Destination "ClientApp.java" -Force
        Move-Item -Path "run.bat.tmp" -Destination "run.bat" -Force
        if (Test-Path "run.ps1.tmp") { Move-Item -Path "run.ps1.tmp" -Destination "run.ps1" -Force }
        Write-Host "[OK] Zeta-NetDesk updated to latest version from GitHub." -ForegroundColor Green
    }
} catch {
    # If offline or network timeout, proceed with local copy
    Write-Host "[-] Using local version (offline / fast launch)." -ForegroundColor Gray
}
Write-Host ""

# Step 2: Check Java Environment
Write-Host "[2/3] Verifying Java Environment..." -ForegroundColor White
$javacPath = Get-JavacExecutable
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
    Write-Host "[i] Automatically installing OpenJDK 17 via winget..." -ForegroundColor Cyan
    try {
        winget install Microsoft.OpenJDK.17 --accept-package-agreements --accept-source-agreements --silent
        $env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
        $javacPath = Get-JavacExecutable
    } catch {
        Write-Host "[X] Automatic installation failed. Please install Java (JDK 8+) from https://adoptium.net" -ForegroundColor Red
        Write-Host "Press any key to exit..." -ForegroundColor Gray
        $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
        exit 1
    }
}

Write-Host "[OK] Java Environment Ready." -ForegroundColor Green
Write-Host ""

# Step 3: Smart Zero-Failure Compilation & Binary Readiness
function Ensure-NetDeskCompiled {
    $hasClasses = (Test-Path "ServerApp.class") -and (Test-Path "ClientApp.class")
    $needsCompile = $false

    if (-not $hasClasses) {
        $needsCompile = $true
    } else {
        if ((Test-Path "ServerApp.java") -and (Test-Path "ClientApp.java")) {
            $serverSrc = (Get-Item "ServerApp.java").LastWriteTime
            $serverCls = (Get-Item "ServerApp.class").LastWriteTime
            $clientSrc = (Get-Item "ClientApp.java").LastWriteTime
            $clientCls = (Get-Item "ClientApp.class").LastWriteTime
            if ($serverSrc -gt $serverCls -or $clientSrc -gt $clientCls) {
                $needsCompile = $true
            }
        }
    }

    if ($needsCompile) {
        $javac = Get-JavacExecutable
        if ($javac) {
            Write-Host "[i] Compiling components (Universal Java 8+ Bytecode)..." -ForegroundColor Cyan
            $compileErr = javac --release 8 -Xlint:-options ServerApp.java ClientApp.java 2>&1
            if ($LASTEXITCODE -ne 0) {
                $compileErr = javac ServerApp.java ClientApp.java 2>&1
            }

            if ($LASTEXITCODE -eq 0 -and (Test-Path "ServerApp.class")) {
                Write-Host "[OK] Components compiled successfully." -ForegroundColor Green
                return
            } else {
                Write-Host "[!] Compilation warning / notice:" -ForegroundColor Yellow
                Write-Host "$compileErr" -ForegroundColor Gray
            }
        }

        # If javac is missing on this machine, verify if pre-compiled .class files exist
        if ($hasClasses) {
            Write-Host "[OK] Pre-compiled universal binaries ready." -ForegroundColor Green
            return
        }

        # If classes do not exist and no compiler, retrieve universal binary package from GitHub
        Write-Host "[!] Pre-compiled classes missing and JDK compiler not found." -ForegroundColor Yellow
        Write-Host "[i] Fetching universal binary package from GitHub..." -ForegroundColor Cyan
        try {
            $zipUrl = "https://github.com/M-Owais-Arshad/Zeta-NetDesk/archive/refs/heads/main.zip"
            $zipPath = Join-Path (Get-Location).Path "netdesk_bin.zip"
            Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath -UseBasicParsing -TimeoutSec 10
            Expand-Archive -Path $zipPath -DestinationPath "temp_unzip" -Force
            Copy-Item -Path "temp_unzip\Zeta-NetDesk-main\*.class" -Destination "." -Force -ErrorAction SilentlyContinue
            Copy-Item -Path "temp_unzip\Zeta-NetDesk-main\*.java" -Destination "." -Force -ErrorAction SilentlyContinue
            Remove-Item -Path "temp_unzip" -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -Path $zipPath -Force -ErrorAction SilentlyContinue
            if (Test-Path "ServerApp.class") {
                Write-Host "[OK] Universal binaries successfully retrieved." -ForegroundColor Green
                return
            }
        } catch {
            Write-Host "[X] Binary download failed: $_" -ForegroundColor Red
        }

        Write-Host "[X] Unable to prepare binaries. Please install JDK from https://adoptium.net" -ForegroundColor Red
        Read-Host "Press Enter to return to menu..."
    }
}

# Supervised Launcher: Protects against silent closes & captures crashes
function Launch-Station([string]$mainClass, [string]$title, [string]$bannerColor) {
    Ensure-NetDeskCompiled
    if (-not (Test-Path "$mainClass.class")) {
        Write-Host "[X] Cannot launch ${title} - $mainClass.class was not found." -ForegroundColor Red
        Read-Host "Press Enter to return to menu..."
        return
    }

    # If Server, auto-release occupied ports (5000, 5001, 5002)
    if ($mainClass -eq "ServerApp") {
        try {
            $conns = Get-NetTCPConnection -LocalPort 5000, 5001, 5002 -ErrorAction SilentlyContinue
            if ($conns) {
                $stalePids = $conns.OwningProcess | Select-Object -Unique
                foreach ($sp in $stalePids) {
                    if ($sp -and $sp -ne $PID) {
                        Stop-Process -Id $sp -Force -ErrorAction SilentlyContinue
                    }
                }
                Start-Sleep -Milliseconds 300
            }
        } catch {}
    }

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor $bannerColor
    Write-Host "  Launching $title ($mainClass)..." -ForegroundColor White
    Write-Host "============================================================" -ForegroundColor $bannerColor
    Write-Host "Active supervisor running. This console will NOT close on error." -ForegroundColor Gray
    Write-Host ""

    # Clear old crash log
    if (Test-Path "netdesk_crash.log") {
        Remove-Item "netdesk_crash.log" -Force -ErrorAction SilentlyContinue
    }

    # Launch Java process with array args to eliminate quote escaping bugs
    $proc = Start-Process -FilePath "java" -ArgumentList @("-cp", ".", $mainClass) -PassThru -ErrorAction SilentlyContinue

    if (-not $proc) {
        Write-Host "[X] Failed to launch Java process." -ForegroundColor Red
        Write-Host "Running directly in current console to diagnose:" -ForegroundColor Yellow
        & java -cp . $mainClass
        Read-Host "Press Enter to continue..."
        return
    }

    # Monitor startup: Give the JVM 1.5 seconds to detect immediate crashes
    Start-Sleep -Milliseconds 1500
    if ($proc.HasExited) {
        $exitCode = $proc.ExitCode
        Write-Host ""
        Write-Host "============================================================" -ForegroundColor Red
        Write-Host "  [CRASH DETECTED] $mainClass terminated unexpectedly!" -ForegroundColor Red
        Write-Host "  Exit Code: $exitCode" -ForegroundColor Red
        Write-Host "============================================================" -ForegroundColor Red
        Write-Host ""

        if (Test-Path "netdesk_crash.log") {
            Write-Host "Diagnostic Details from 'netdesk_crash.log':" -ForegroundColor Yellow
            Get-Content "netdesk_crash.log" -Tail 25 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
            Write-Host ""
        }

        Write-Host "Diagnostic: Direct console output from JVM:" -ForegroundColor Yellow
        & java -cp . $mainClass
        Write-Host ""
        Write-Host "============================================================" -ForegroundColor Gray
        Read-Host "Press Enter to return to menu..."
        return
    }

    Write-Host "[OK] $title is active and running (Process ID: $($proc.Id))." -ForegroundColor Green
    Write-Host "You may keep this supervisor window open or return to menu." -ForegroundColor Gray
    Write-Host ""
    Read-Host "Press Enter to return to launcher menu..."
}

# Step 4: Interactive Menu
while ($true) {
    Write-Host "Select an option to launch:" -ForegroundColor White
    Write-Host "  [1] Launch SERVER      (Host Machine / Control Screen)" -ForegroundColor Yellow
    Write-Host "  [2] Launch CLIENT      (Share Screen & Accept Remote Control)" -ForegroundColor Green
    Write-Host "  [3] Update Zeta-NetDesk (Pull latest version from GitHub)" -ForegroundColor Cyan
    Write-Host "  [4] Exit" -ForegroundColor Gray
    Write-Host ""
    $choice = Read-Host "Enter your choice [1-4]"

    switch ($choice) {
        "1" {
            Launch-Station "ServerApp" "SERVER Station" "Yellow"
        }
        "2" {
            Launch-Station "ClientApp" "CLIENT Station" "Green"
        }
        "3" {
            Write-Host ""
            Write-Host "[i] Fetching latest files from GitHub..." -ForegroundColor Cyan
            try {
                if (Test-Path ".git") {
                    git pull
                } else {
                    $rawBase = "https://raw.githubusercontent.com/M-Owais-Arshad/Zeta-NetDesk/main"
                    Invoke-WebRequest -Uri "$rawBase/ServerApp.java" -OutFile "ServerApp.java" -UseBasicParsing
                    Invoke-WebRequest -Uri "$rawBase/ClientApp.java" -OutFile "ClientApp.java" -UseBasicParsing
                    Invoke-WebRequest -Uri "$rawBase/run.bat" -OutFile "run.bat" -UseBasicParsing
                    Invoke-WebRequest -Uri "$rawBase/run.ps1" -OutFile "run.ps1" -UseBasicParsing
                }
                Write-Host "[OK] Source and launcher files updated." -ForegroundColor Green
                Write-Host "[i] Recompiling components..." -ForegroundColor Cyan
                $javac = Get-JavacExecutable
                if ($javac) {
                    javac --release 8 -Xlint:-options ServerApp.java ClientApp.java
                    if ($LASTEXITCODE -eq 0) {
                        Write-Host "[OK] Zeta-NetDesk successfully updated and recompiled!" -ForegroundColor Green
                    }
                } else {
                    Write-Host "[OK] Zeta-NetDesk updated. Using universal binaries." -ForegroundColor Green
                }
            } catch {
                Write-Host "[X] Update failed: $_" -ForegroundColor Red
            }
            Write-Host ""
        }
        "4" {
            Write-Host "Exiting Zeta-NetDesk..." -ForegroundColor Gray
            exit 0
        }
        default {
            Write-Host "Invalid choice. Please enter 1, 2, 3, or 4." -ForegroundColor Red
            Write-Host ""
        }
    }
}
