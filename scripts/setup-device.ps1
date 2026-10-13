<#
.SYNOPSIS
    One-click setup & deployment script for Anysecu Walkie-Talkies (G393 / Android 8.1).
#>
param(
    [string]$DeviceId = ""
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir
$PttApk = Join-Path $BaseDir "releases\AnysecuPttBridge-v2.6.apk"
if (-not (Test-Path $PttApk)) {
    $PttApk = Join-Path $BaseDir "releases\AnysecuPttBridge-v2.5.apk"
}
$MumlaApk = Join-Path $BaseDir "releases\mumla-v3.7.3.apk"

# Check adb command
$adb = "adb"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $fallbackAdb = "C:\Users\Sam\.gemini\antigravity\scratch\platform-tools\adb.exe"
    if (Test-Path $fallbackAdb) {
        $adb = $fallbackAdb
    } else {
        Write-Error "Cannot find adb command. Please ensure platform-tools is in PATH."
        exit 1
    }
}

# Get connected devices
$rawDevices = & $adb devices | Where-Object { $_ -match "\bdevice$" }
$devices = @()
foreach ($d in $rawDevices) {
    $devices += ($d -split "\s+")[0]
}

if ($devices.Count -eq 0) {
    Write-Error "No Android devices connected. Please connect walkie-talkie with USB debugging enabled."
    exit 1
}

$targetDevice = if ($DeviceId) { $DeviceId } else { $devices[0] }
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Anysecu PTT Device Auto Setup (v2.6)    " -ForegroundColor Cyan
Write-Host "  Target Device: $targetDevice            " -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Cyan

# 1. Disable conflicting stock Zello
Write-Host "[1/7] Disabling stock Zello..." -ForegroundColor Green
& $adb -s $targetDevice shell pm disable-user com.loudtalks | Out-Null
& $adb -s $targetDevice shell pm disable-user com.zello.ptt | Out-Null

# 2. Adjust resolution to 200x240 @ 80dpi
Write-Host "[2/7] Setting display to 200x240 @ 80dpi..." -ForegroundColor Green
& $adb -s $targetDevice shell wm size 200x240
& $adb -s $targetDevice shell wm density 80

# 3. Install Mumla
if (Test-Path $MumlaApk) {
    Write-Host "[3/7] Installing Mumla 3.7.3..." -ForegroundColor Green
    & $adb -s $targetDevice install -r $MumlaApk
} else {
    Write-Host "[3/7] Skipping Mumla install (releases\mumla-v3.7.3.apk not found)" -ForegroundColor Yellow
}

# 4. Install Anysecu PTT Bridge
if (Test-Path $PttApk) {
    Write-Host "[4/7] Installing Anysecu PTT Bridge (v2.6)..." -ForegroundColor Green
    & $adb -s $targetDevice install -r $PttApk
} else {
    Write-Error "PTT Bridge APK not found: $PttApk"
    exit 1
}

# 5. Grant READ_LOGS and Doze Whitelist
Write-Host "[5/7] Granting READ_LOGS & Doze whitelist..." -ForegroundColor Green
& $adb -s $targetDevice shell pm grant com.anysecu.pttbridge android.permission.READ_LOGS
& $adb -s $targetDevice shell dumpsys deviceidle whitelist +com.anysecu.pttbridge | Out-Null
& $adb -s $targetDevice shell dumpsys deviceidle whitelist +se.lublin.mumla | Out-Null
& $adb -s $targetDevice shell cmd appops set com.anysecu.pttbridge RUN_IN_BACKGROUND allow
& $adb -s $targetDevice shell cmd appops set se.lublin.mumla RUN_IN_BACKGROUND allow
& $adb -s $targetDevice shell am set-inactive com.anysecu.pttbridge false

# 6. Start PTT Bridge Service
Write-Host "[6/7] Starting PTT Bridge Foreground Service..." -ForegroundColor Green
& $adb -s $targetDevice shell am start-foreground-service -n com.anysecu.pttbridge/.PttBridgeService | Out-Null

Write-Host "==========================================" -ForegroundColor Green
Write-Host "  Deployment Complete (v2.6)!" -ForegroundColor Green
Write-Host "  - PTT: Zero-latency + ALSA-BCLO + TOT 60s + Pocket Lock" -ForegroundColor Green
Write-Host "  - Keep-Alive: Doze whitelist + Auto heal" -ForegroundColor Green
Write-Host "  - Watchdog: Infinite Reconnect on network drops" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
