@echo off
setlocal
title Giftia - Spring Boot and Memory Garden
cd /d "%~dp0"
set "GIFTIA_LAUNCH_ROOT=%CD%"

echo ============================================
echo    Giftia - Java Backend and Memory Garden
echo ============================================

if not exist ".env" (
    echo [ERROR] .env not found. Run: copy .env.example .env
    exit /b 1
)
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Install JDK 17 or newer and add Java to PATH.
    exit /b 1
)
where javac >nul 2>&1
if errorlevel 1 (
    echo [ERROR] A full JDK 17+ is required, including javac.
    exit /b 1
)
powershell.exe -NoProfile -Command "$v = (& java -version 2>&1 | Out-String); if ($v -notmatch 'version\D+(\d+)' -or [int]$Matches[1] -lt 17) { Write-Host '[ERROR] JDK 17 or newer is required.'; exit 1 }"
if errorlevel 1 exit /b 1
where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Install Node.js 18 or newer.
    exit /b 1
)
where npm >nul 2>&1
if errorlevel 1 (
    echo [ERROR] npm is required with Node.js.
    exit /b 1
)
node -e "if (Number(process.versions.node.split('.')[0]) < 18) { console.error('[ERROR] Node.js 18 or newer is required.'); process.exit(1); }"
if errorlevel 1 exit /b 1
if not exist "backend-java\mvnw.cmd" (
    echo [ERROR] backend-java\mvnw.cmd is missing. Restore the Maven wrapper files.
    exit /b 1
)

echo [1/4] Checking ports 8000 and 3000...
powershell.exe -NoProfile -Command "$busy = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 8000,3000 }); if ($busy.Count) { $busy | Select-Object LocalAddress,LocalPort,OwningProcess | Format-Table; Write-Host '[ERROR] A required port is occupied. Stop the intended existing service before retrying.'; exit 1 }"
if errorlevel 1 exit /b 1

echo [2/4] Packaging the Spring Boot backend...
pushd "backend-java"
call mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
if errorlevel 1 (
    popd
    echo [ERROR] Backend build failed. No services were started.
    exit /b 1
)
popd

echo [3/4] Checking frontend dependencies...
if not exist "frontend\node_modules" (
    pushd "frontend"
    call npm install
    if errorlevel 1 (
        popd
        echo [ERROR] Frontend dependency installation failed.
        exit /b 1
    )
    popd
)
if not exist ".run" mkdir ".run"
if not exist ".run" (
    echo [ERROR] Cannot create the local log directory.
    exit /b 1
)

echo [4/4] Starting background services...
powershell.exe -NoProfile -Command "$ErrorActionPreference = 'Stop'; $root = $env:GIFTIA_LAUNCH_ROOT; $logs = Join-Path $root '.run'; $backend = $null; $frontend = $null; try { $backend = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar','backend-java/target/giftia-backend-1.0.0.jar','--server.address=127.0.0.1','--server.port=8000') -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'backend.log') -RedirectStandardError (Join-Path $logs 'backend.err.log') -PassThru; $frontend = Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','3000','--strictPort') -WorkingDirectory (Join-Path $root 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'frontend.log') -RedirectStandardError (Join-Path $logs 'frontend.err.log') -PassThru; Start-Sleep -Seconds 2; $backend.Refresh(); $frontend.Refresh(); if ($backend.HasExited -or $frontend.HasExited) { throw 'A service exited during startup. Check the .run log files.' }; Set-Content -LiteralPath (Join-Path $logs 'backend.pid') -Value $backend.Id; Set-Content -LiteralPath (Join-Path $logs 'frontend.pid') -Value $frontend.Id; Write-Host ('Backend PID: ' + $backend.Id); Write-Host ('Frontend PID: ' + $frontend.Id) } catch { foreach ($child in @($backend,$frontend)) { if ($null -ne $child -and -not $child.HasExited) { Stop-Process -Id $child.Id -ErrorAction SilentlyContinue } }; Write-Host ('[ERROR] ' + $_.Exception.Message); exit 1 }"
if errorlevel 1 exit /b 1

echo.
echo Services are starting. Logs and PIDs: .run
echo Frontend: http://127.0.0.1:3000
echo Backend:  http://127.0.0.1:8000
echo Health:   http://127.0.0.1:8000/api/health
echo Closing this launcher leaves the background services running.
echo Use the printed PIDs to identify and stop these services when finished.
endlocal
