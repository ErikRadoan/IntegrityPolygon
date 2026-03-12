﻿<#
.SYNOPSIS
    Deploy & test IntegrityPolygon in Docker (Velocity + Paper)

.DESCRIPTION
    Builds all JARs, copies them to the Docker volume directories,
    and starts the docker-compose environment.

.NOTES
    Prerequisites:
      - Docker Desktop running
      - Maven available (IntelliJ bundled)
      - Java 17+ (for building)

    Usage:
      .\deploy.ps1 -Build -Start    # Full build + deploy + start
      .\deploy.ps1 -Start           # Deploy (no build) + start
      .\deploy.ps1 -Logs            # View container logs
      .\deploy.ps1 -Stop            # Stop containers
      .\deploy.ps1 -Clean           # Remove containers + volumes
      .\deploy.ps1 -SyncSecret      # Copy extender secret from proxy config to Paper config
#>

param(
    [switch]$Build,
    [switch]$Start,
    [switch]$Stop,
    [switch]$Logs,
    [switch]$Clean,
    [switch]$SyncSecret
)

$Root = Split-Path -Parent $PSScriptRoot
if (-not $Root) { $Root = (Get-Location).Path }
$TestEnv = Join-Path $Root "test-env"
$MVN = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd"

function Remove-BOM($filePath) {
    $b = [System.IO.File]::ReadAllBytes($filePath)
    if ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF) {
        $n = New-Object byte[] ($b.Length - 3)
        [System.Buffer]::BlockCopy($b, 3, $n, 0, $n.Length)
        [System.IO.File]::WriteAllBytes($filePath, $n)
    }
}

function Invoke-Mvn {
    param([string]$WorkDir, [string]$MvnArgs, [string]$Label)
    Write-Host "  Building $Label..." -ForegroundColor Yellow
    $logFile = Join-Path $TestEnv "build-$($Label -replace '\s+','-').log"
    cmd /c "set ""JAVA_HOME=C:\Program Files\Java\jdk-25.0.2"" && cd /d ""$WorkDir"" && ""$MVN"" $MvnArgs > ""$logFile"" 2>&1"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  FAILED: $Label (see $logFile)" -ForegroundColor Red
        Get-Content $logFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkRed }
        return $false
    }
    Write-Host "  OK: $Label" -ForegroundColor Green
    return $true
}

function Build-All {
    Write-Host "`n=== Building IntegrityPolygon ===" -ForegroundColor Cyan

    # Remove BOM from all Java/YML source files (Windows PowerShell adds BOM on file create)
    Write-Host "  Stripping BOMs from source files..." -ForegroundColor DarkGray
    Get-ChildItem -Path $Root -Recurse -Include "*.java","*.yml","*.html" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notlike "*\target\*" } |
        ForEach-Object { Remove-BOM $_.FullName }

    $ok = $true

    # Main plugin (install to local repo so modules can resolve it)
    if (-not (Invoke-Mvn -WorkDir $Root -MvnArgs "clean package install -DskipTests" -Label "main plugin")) { $ok = $false }

    # Extender
    if (-not (Invoke-Mvn -WorkDir (Join-Path $Root "extender") -MvnArgs "clean package -DskipTests" -Label "extender")) { $ok = $false }

    # Paper-side profiler extender module (must be built before the profiler Velocity module bundles it)
    $profilerExtDir = Join-Path $Root "modules\profiler\profiler-extender"
    if (Test-Path $profilerExtDir) {
        if (-not (Invoke-Mvn -WorkDir $profilerExtDir -MvnArgs "clean package -DskipTests" -Label "module profiler-extender")) { $ok = $false }
    }

    # Modules
    foreach ($mod in @("anti-bot", "account-protection", "identity-enforcement", "geo-filtering", "server-monitor", "profiler")) {
        $modDir = Join-Path $Root "modules\$mod"
        if (Test-Path $modDir) {
            if (-not (Invoke-Mvn -WorkDir $modDir -MvnArgs "clean package -DskipTests" -Label "module $mod")) { $ok = $false }
        }
    }

    if ($ok) {
        Write-Host "`n  All builds successful!" -ForegroundColor Green
    } else {
        Write-Host "`n  Some builds failed. Check logs above." -ForegroundColor Red
    }
    return $ok
}

function Deploy-Plugins {
    Write-Host "`n=== Deploying plugins ===" -ForegroundColor Cyan

    $velPlugins = Join-Path $TestEnv "velocity\plugins"
    $papPlugins = Join-Path $TestEnv "paper\plugins"
    $modDir = Join-Path $velPlugins "integritypolygon\modules"

    New-Item -ItemType Directory -Force -Path $velPlugins | Out-Null
    New-Item -ItemType Directory -Force -Path $papPlugins | Out-Null
    New-Item -ItemType Directory -Force -Path $modDir | Out-Null

    # Main plugin -> Velocity
    $mainJar = Get-ChildItem (Join-Path $Root "target\integritypolygon-*.jar") -Exclude "original-*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($mainJar) {
        Copy-Item $mainJar.FullName (Join-Path $velPlugins "integritypolygon.jar") -Force
        Write-Host "  Deployed: $($mainJar.Name) -> velocity/plugins/" -ForegroundColor Green
    } else { Write-Host "  WARNING: Main plugin JAR not found! Run with -Build first." -ForegroundColor Red }

    # Extender -> Paper
    $extJar = Get-ChildItem (Join-Path $Root "extender\target\integritypolygon-extender-*.jar") -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($extJar) {
        Copy-Item $extJar.FullName (Join-Path $papPlugins "integritypolygon-extender.jar") -Force
        Write-Host "  Deployed: $($extJar.Name) -> paper/plugins/" -ForegroundColor Green
    } else { Write-Host "  WARNING: Extender JAR not found!" -ForegroundColor Red }

    # Modules -> Velocity modules dir
    foreach ($mod in @("anti-bot", "account-protection", "identity-enforcement", "geo-filtering", "server-monitor", "profiler")) {
        $jar = Get-ChildItem (Join-Path $Root "modules\$mod\target\$mod-*.jar") -Exclude "original-*" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($jar) {
            Copy-Item $jar.FullName (Join-Path $modDir "$mod.jar") -Force
            Write-Host "  Deployed: $($jar.Name) -> modules/" -ForegroundColor Green
        }
    }

    Write-Host "  Deployment complete!" -ForegroundColor Green
}

function Sync-ExtenderSecret {
    Write-Host "`n=== Syncing extender secret ===" -ForegroundColor Cyan

    # Read the proxy config from the running Velocity container
    $configRaw = cmd /c "docker exec ip-velocity cat /server/plugins/integritypolygon/config.yml" 2>$null
    if (-not $configRaw) {
        Write-Host "  Could not read config from proxy container." -ForegroundColor Red
        Write-Host "  Make sure the Velocity container (ip-velocity) is running." -ForegroundColor Yellow
        Write-Host "  The proxy must have started at least once to generate the secret." -ForegroundColor Yellow
        return
    }

    # The extender secret is a 32-char hex string in the extender section
    # Config format: extender: {secret: d2a02c5f2c824a22b3bd6f1ced83d4b6, ...}
    $configText = $configRaw -join "`n"
    $secret = $null
    if ($configText -match 'extender:\s*\{[^}]*secret:\s*([a-f0-9]{32})') {
        $secret = $Matches[1]
    } elseif ($configText -match 'extender:[\s\S]*?secret:\s*[''"]?([a-f0-9]{32})[''"]?') {
        $secret = $Matches[1]
    }

    if (-not $secret) {
        Write-Host "  Could not find extender secret in proxy config." -ForegroundColor Red
        return
    }

    Write-Host "  Found secret: $secret" -ForegroundColor Green

    # Write the extender config file locally
    $extConfig = Join-Path $TestEnv "paper\plugins\IntegrityPolygon-Extender\config.yml"
    $extConfigDir = Split-Path $extConfig -Parent
    New-Item -ItemType Directory -Force -Path $extConfigDir | Out-Null

    $content = "# Auto-synced by deploy.ps1`nproxy-host: ""velocity""`nproxy-port: 3491`nsecret: ""$secret""`n"
    [System.IO.File]::WriteAllText($extConfig, $content, [System.Text.UTF8Encoding]::new($false))

    Write-Host "  Wrote config to paper/plugins/IntegrityPolygon-Extender/config.yml" -ForegroundColor Green
    Write-Host "  Restarting Paper container..." -ForegroundColor Yellow
    docker restart ip-paper 2>$null | Out-Null
    Write-Host "  Done! Paper will reconnect with the correct secret." -ForegroundColor Green
}

function Start-Env {
    Write-Host "`n=== Starting Docker environment ===" -ForegroundColor Cyan
    Push-Location $TestEnv
    docker-compose up -d 2>&1 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    Pop-Location

    Write-Host ""
    Write-Host "  ┌──────────────────────────────────────────────┐" -ForegroundColor Cyan
    Write-Host "  │  Velocity proxy : localhost:25577 (Minecraft)│" -ForegroundColor Green
    Write-Host "  │  Web panel      : http://localhost:3490      │" -ForegroundColor Green
    Write-Host "  │  Extender socket: port 3491 (internal)       │" -ForegroundColor Green
    Write-Host "  └──────────────────────────────────────────────┘" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  FIRST RUN:" -ForegroundColor Yellow
    Write-Host "    1. Wait ~30s for both servers to start" -ForegroundColor Yellow
    Write-Host "    2. Run: .\deploy.ps1 -SyncSecret" -ForegroundColor Yellow
    Write-Host "    3. Open http://localhost:3490 and check console for admin credentials" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  View logs : .\deploy.ps1 -Logs" -ForegroundColor DarkGray
    Write-Host "  Stop      : .\deploy.ps1 -Stop" -ForegroundColor DarkGray
}

function Stop-Env {
    Write-Host "`n=== Stopping Docker environment ===" -ForegroundColor Cyan
    Push-Location $TestEnv
    docker-compose stop 2>&1 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    Pop-Location
    Write-Host "  Stopped." -ForegroundColor Green
}

function Show-Logs {
    Push-Location $TestEnv
    docker-compose logs -f --tail 100
    Pop-Location
}

function Clean-Env {
    Write-Host "`n=== Cleaning Docker environment ===" -ForegroundColor Cyan
    Push-Location $TestEnv
    docker-compose down -v 2>&1 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    Pop-Location
    Write-Host "  Cleaned." -ForegroundColor Green
}

# ── Main ──
if ($Build) { Build-All }
if ($Build -or $Start -or $SyncSecret) { Deploy-Plugins }
if ($SyncSecret) { Sync-ExtenderSecret }
if ($Start) { Start-Env }
if ($Stop) { Stop-Env }
if ($Logs) { Show-Logs }
if ($Clean) { Clean-Env }

if (-not $Build -and -not $Start -and -not $Stop -and -not $Logs -and -not $Clean -and -not $SyncSecret) {
    Write-Host ""
    Write-Host "  IntegrityPolygon Test Environment" -ForegroundColor Cyan
    Write-Host "  ─────────────────────────────────" -ForegroundColor DarkGray
    Write-Host "  .\deploy.ps1 -Build -Start    # Build + deploy + start" -ForegroundColor White
    Write-Host "  .\deploy.ps1 -SyncSecret      # Sync extender secret (after first start)" -ForegroundColor White
    Write-Host "  .\deploy.ps1 -Logs            # View live logs" -ForegroundColor White
    Write-Host "  .\deploy.ps1 -Stop            # Stop containers" -ForegroundColor White
    Write-Host "  .\deploy.ps1 -Clean           # Remove everything" -ForegroundColor White
    Write-Host ""
}
