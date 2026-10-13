<#
.SYNOPSIS
    Builds and signs AnysecuPttBridge APK without Android Studio.
#>
param(
    [string]$AndroidJar = "C:\Scoop\apps\android-clt\current\platforms\android-28\android.jar",
    [string]$BuildToolsDir = "C:\Scoop\apps\android-clt\current\build-tools\28.0.3",
    [string]$JavaHome = "C:\Scoop\apps\temurin17-jdk\current"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# 1. 偵測工具路徑
if (-not (Test-Path $AndroidJar)) {
    if ($env:ANDROID_HOME) {
        $AndroidJar = Get-ChildItem -Path "$env:ANDROID_HOME\platforms\android-*\android.jar" | Select-Object -Last 1 -ExpandProperty FullName
    }
}

if (-not (Test-Path $BuildToolsDir)) {
    if ($env:ANDROID_HOME) {
        $BuildToolsDir = Get-ChildItem -Path "$env:ANDROID_HOME\build-tools\*" | Select-Object -Last 1 -ExpandProperty FullName
    }
}

$Aapt = Join-Path $BuildToolsDir "aapt.exe"
$D8Jar = Join-Path $BuildToolsDir "lib\d8.jar"
$ApkSignerJar = Join-Path $BuildToolsDir "lib\apksigner.jar"
$Javac = if (Test-Path "$JavaHome\bin\javac.exe") { "$JavaHome\bin\javac.exe" } else { "javac" }
$Java = if (Test-Path "$JavaHome\bin\java.exe") { "$JavaHome\bin\java.exe" } else { "java" }
$Keystore = Join-Path $ScriptDir "debug.keystore"
$BinDir = Join-Path $ScriptDir "bin"
$GenDir = Join-Path $ScriptDir "gen"
$ResDir = Join-Path $ScriptDir "res"
$OutApk = Join-Path $ScriptDir "releases\AnysecuPttBridge-v2.5.apk"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Building Anysecu PTT Bridge (v2.5)      " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# 2. 清理與建立目錄
if (Test-Path $BinDir) { Remove-Item -Recurse -Force $BinDir }
if (Test-Path $GenDir) { Remove-Item -Recurse -Force $GenDir }
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
New-Item -ItemType Directory -Force -Path $GenDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ScriptDir "releases") | Out-Null

# 3. 如果 keystore 不存在則自動生成
if (-not (Test-Path $Keystore)) {
    Write-Host "[*] Generating debug.keystore..." -ForegroundColor Yellow
    $Keytool = if (Test-Path "$JavaHome\bin\keytool.exe") { "$JavaHome\bin\keytool.exe" } else { "keytool" }
    & $Keytool -genkeypair -v -keystore $Keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}

# 4. 生成 R.java (如果有 res 目錄)
if (Test-Path $ResDir) {
    Write-Host "[*] Generating R.java from resources..." -ForegroundColor Green
    & $Aapt package -f -m -J $GenDir -S $ResDir -M "$ScriptDir\AndroidManifest.xml" -I $AndroidJar
}

# 5. 編譯 Java 代碼 (目標 Java 8 以確保相容 Android 8.1 / d8)
Write-Host "[*] Compiling Java source files..." -ForegroundColor Green
$JavaFiles = (Get-ChildItem -Path @("$ScriptDir\src", "$GenDir") -Filter "*.java" -Recurse -File).FullName
& $Javac -source 8 -target 8 -encoding UTF-8 -cp $AndroidJar -d $BinDir $JavaFiles

# 6. D8 編譯為 classes.dex
Write-Host "[*] Converting class files to DEX (D8)..." -ForegroundColor Green
$ClassFiles = (Get-ChildItem -Recurse "$BinDir\*.class").FullName
& $Java -cp $D8Jar com.android.tools.r8.D8 --output $BinDir --lib $AndroidJar $ClassFiles

# 7. AAPT 打包未簽名 APK (含 resources)
Write-Host "[*] Packaging resource & manifest (AAPT)..." -ForegroundColor Green
$UnsignedApk = Join-Path $BinDir "unsigned.apk"
if (Test-Path $ResDir) {
    & $Aapt package -f -m -F $UnsignedApk -S $ResDir -M "$ScriptDir\AndroidManifest.xml" -I $AndroidJar
} else {
    & $Aapt package -f -m -F $UnsignedApk -M "$ScriptDir\AndroidManifest.xml" -I $AndroidJar
}

# 8. 將 classes.dex 加入 APK
Write-Host "[*] Adding classes.dex to APK..." -ForegroundColor Green
Push-Location $BinDir
& $Aapt add -k "unsigned.apk" "classes.dex"
Pop-Location

# 9. 簽名 APK
Write-Host "[*] Signing APK (ApkSigner)..." -ForegroundColor Green
& $Java -jar $ApkSignerJar sign --ks $Keystore --ks-pass pass:android --out $OutApk $UnsignedApk

Write-Host "==========================================" -ForegroundColor Green
Write-Host "  Build Complete: $OutApk" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
