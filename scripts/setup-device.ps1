<#
.SYNOPSIS
    One-click setup & deployment script for Anysecu Walkie-Talkies (G393 / Android 8.1).
#>
param(
    [string]$DeviceId = ""
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir
$PttApk = Join-Path $BaseDir "releases\AnysecuPttBridge-v1.4.apk"
if (-not (Test-Path $PttApk)) {
    $PttApk = Join-Path $BaseDir "releases\AnysecuPttBridge-v1.3.apk"
}
$MumlaApk = Join-Path $BaseDir "releases\mumla-v3.7.3.apk"

# 檢查 adb 指令
$adb = "adb"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $fallbackAdb = "C:\Users\Sam\.gemini\antigravity\scratch\platform-tools\adb.exe"
    if (Test-Path $fallbackAdb) {
        $adb = $fallbackAdb
    } else {
        Write-Error "找不到 adb 指令，請確認 Android SDK platform-tools 已加入 PATH。"
        exit 1
    }
}

# 取得連接的裝置
$devices = & $adb devices | Where-Object { $_ -match "\bdevice$" } | ForEach-Object { ($_ -split "\s+")[0] }
if ($devices.Count -eq 0) {
    Write-Error "未偵測到任何 Android 裝置，請連接對講機並開啟 USB 偵錯模式。"
    exit 1
}

$targetDevice = if ($DeviceId) { $DeviceId } else { $devices[0] }
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Anysecu 對講機自動設定工具 (v1.4)" -ForegroundColor Cyan
Write-Host "  目標裝置: $targetDevice" -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Cyan

# 1. 停用衝突的內建 Zello
Write-Host "[1/7] 停用內建 Zello (釋放硬體鍵與防干擾)..." -ForegroundColor Green
& $adb -s $targetDevice shell pm disable-user com.loudtalks | Out-Null
& $adb -s $targetDevice shell pm disable-user com.zello.ptt | Out-Null

# 2. 調整解析度至 200x240 (讓 Mumla 伺服器編輯頁面能完整顯示帳號密碼欄位)
Write-Host "[2/7] 調整虛擬解析度至 200x240 (解決小螢幕無法輸入帳密問題)..." -ForegroundColor Green
& $adb -s $targetDevice shell wm size 200x240
& $adb -s $targetDevice shell wm density 72

# 3. 安裝 Mumla
if (Test-Path $MumlaApk) {
    Write-Host "[3/7] 安裝 Mumla 3.7.3..." -ForegroundColor Green
    & $adb -s $targetDevice install -r $MumlaApk
} else {
    Write-Host "[3/7] 跳過 Mumla 安裝 (未找到 releases\mumla-v3.7.3.apk)" -ForegroundColor Yellow
}

# 4. 安裝 Anysecu PTT Bridge
if (Test-Path $PttApk) {
    Write-Host "[4/7] 安裝 Anysecu PTT Bridge (v1.4)..." -ForegroundColor Green
    & $adb -s $targetDevice install -r $PttApk
} else {
    Write-Error "未找到 PTT Bridge APK: $PttApk"
    exit 1
}

# 5. 授予 READ_LOGS 特殊權限
Write-Host "[5/6] 授予 READ_LOGS 特殊權限..." -ForegroundColor Green
& $adb -s $targetDevice shell pm grant com.anysecu.pttbridge android.permission.READ_LOGS

# 6. 啟動 PTT Bridge 服務
Write-Host "[6/6] 啟動 PTT Bridge 背景常駐守護服務..." -ForegroundColor Green
& $adb -s $targetDevice shell am start -n com.anysecu.pttbridge/.MainActivity | Out-Null

Write-Host "==========================================" -ForegroundColor Green
Write-Host "  部署完成！" -ForegroundColor Green
Write-Host "  - PTT 側鍵: 零延遲直通 + 硬體級 ALSA-BCLO 佔線禁發" -ForegroundColor Green
Write-Host "  - 口袋防誤觸鎖定: 長按側邊音量下鍵進入 (關閉顯示全黑遮蔽，正面鍵全阻擋)" -ForegroundColor Green
Write-Host "  - 解除鎖定: 連續按側邊音量下鍵 5 次解鎖退出 (具備盲操短嗶音反饋)" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green

