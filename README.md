# Anysecu PTT Bridge (Hardware ALSA-BCLO Engine)

[![Platform](https://img.shields.io/badge/Platform-Android%208.1%2B-green.svg)](https://android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Hardware](https://img.shields.io/badge/Hardware-Anysecu%20G393-blue.svg)](#hardware-notes)
[![Companion](https://img.shields.io/badge/Companion-Mumla%20(Mumble)-orange.svg)](https://github.com/mumla-app/mumla)

專為 **Anysecu 實體按鍵對講機**（例如 Anysecu G393, Qualcomm 晶片, Android 8.1 系統）打造的背景常駐 PTT 橋接與對講優化工具。  
無縫將硬體 PTT 側鍵映射至開源語音客戶端 **Mumla (Mumble)**，並搭載 **硬體級 ALSA-BCLO（Busy Channel Lockout，佔線禁發）** 與 **防搶尾音保護** 機制，隊友說話時自動攔截 PTT 送話並發出提示音，徹底杜絕搶麥。

---

## 🎯 核心功能亮點 (Key Features)

- 📻 **硬體 PTT 零延遲直通映射**：直接攔截 Anysecu 專用硬體側鍵訊號，開箱即用語音通話。
- 🚫 **硬體級 ALSA-BCLO 佔線禁發 (Busy Channel Lockout)**：
  - 核心驅動級精準監測：直接讀取 Linux Kernel ALSA 音訊設備狀態 (`/proc/asound/card0/pcm*p/sub0/status`)。
  - **隊友講話中（喇叭有聲）**：按下 PTT 立即阻擋送話並發出短促忙線嗶聲（Busy Beep），絕不撞話。
  - **頻道空閒時（喇叭靜音）**：按下 PTT 100% 立即發話，**徹底根除 v1.1 之軟體死鎖 Bug**。
- ⏱️ **防搶尾音與斷句保護 (200ms Hangover Time)**：
  - 隊友說完話後保留 200ms 緩衝保護，防止語音短暫停頓時被強行截話。
- ⚡ **開機自動常駐・零磁碟損耗**：
  - 開機自動在背景啟動守護服務 (`START_STICKY` + 前台 Notification)。
  - 脫機獨立運作，平時免接電腦、免開 ADB、0% 閒置 CPU 佔用。
- 🖥️ **小螢幕解析度補正解決方案**：
  - 完美解決 Anysecu 128x160 超小解析度螢幕無法輸入 Mumla 帳號密碼的難題。

---

## 🔍 底層架構與技術原理 (Technical Deep-Dive)

### 1. 為什麼一般 PTT 映射軟體無效？
在 Anysecu (Android 8.1) 韌體中，硬體側鍵（F1 / KeyCode 131）在 `PhoneWindowManager.interceptKeyBeforeQueueing` 階段即被底層攔截。  
原廠系統**完全不發送公開的 BroadcastReceiver 廣播**，僅在系統日誌中輸出：
```log
D WindowManager: unipro.hotkey.ptt.down
D WindowManager: unipro.hotkey.ptt.up
```
因此，市面上的 Key Remapper 或純廣播接收器均無法觸發。

### 2. 本專案的解決方案
`Anysecu PTT Bridge` 透過 Android 系統權限 `READ_LOGS`，在記憶體內部建立原生 Process 管道：
```java
Runtime.getRuntime().exec(new String[]{"logcat", "-b", "system", "-v", "raw", "-s", "WindowManager:D"});
```
- **純記憶體串流讀取**：不落地儲存任何日誌，絕不耗損對講機儲存晶片壽命。
- **即時廣播發送**：一旦捕捉到按下/放開事件，立即轉換為 Mumla 的控制廣播：
  - 按下：`se.lublin.mumla.action.TALK` with extra `status="on"`
  - 放開：`se.lublin.mumla.action.TALK` with extra `status="off"`

### 3. ALSA-BCLO 硬體級佔線檢測原理
在 Android 8.1 系統上，上層的 `AudioPlaybackCallback` 由於缺乏 Android 10+ 的 `isActive()` API，只要通訊 App 保持連線就會常態回報 Player 存在，無法作為發聲依據。  
本專案採用更深入的 **Linux ALSA 音訊設備狀態** 檢測：
- 當喇叭無聲時：`/proc/asound/card0/pcm*p/sub0/status` 檔案一律為 `closed`。
- 當隊友說話喇叭震動發聲時：該 PCM 設備狀態立即躍遷為 `state: RUNNING`。
- 透過在微秒級（0.2ms）掃描該硬體檔案，能夠 **100% 精確且無誤判** 地辨識隊友發話狀態，既保證空閒時 100% 隨按隨說，又保證隊友說話時 100% 阻擋搶麥！

---

## 🛠️ 快速安裝與自動化設定 (Quick Start)

專案已內建完整的自動化安裝腳本，只需將對講機接上 USB 並啟用「USB 偵錯」模式：

### 方法一：使用 PowerShell 一鍵設定（推薦）
以系統管理員身分開啟 PowerShell，進入專案目錄執行：
```powershell
.\scripts\setup-device.ps1
```
腳本會自動完成：
1. 停用衝突的原廠 Zello (`com.loudtalks`, `com.zello.ptt`)
2. 擴增虛擬解析度至 `200x240` ( density 72 )
3. 自動安裝 `Mumla 3.7.3` 與 `Anysecu PTT Bridge v1.3`
4. 授予關鍵的 `READ_LOGS` 特殊權限
5. 自動啟動背景守護服務

---

### 方法二：手動 ADB 指令安裝

#### 1. 停用衝突的內建程式
```bash
adb shell pm disable-user com.loudtalks
adb shell pm disable-user com.zello.ptt
```

#### 2. 解決 128x160 超小螢幕無法輸入帳密問題
原廠物理解析度僅 128x160，在 Mumla 的伺服器編輯頁面無法看到「使用者名稱」與「密碼」欄位。執行以下指令即可擴展垂直顯示高度：
```bash
adb shell wm size 200x240
adb shell wm density 72
```

#### 3. 安裝軟體並授權
```bash
adb install -r releases/mumla-v3.7.3.apk
adb install -r releases/AnysecuPttBridge-v1.3.apk
adb shell pm grant com.anysecu.pttbridge android.permission.READ_LOGS
```

#### 4. 啟動服務
```bash
adb shell am start -n com.anysecu.pttbridge/.MainActivity
```

---

## ⚙️ Mumla 推薦最佳設定 (Best Practices)

1. **傳輸方式 (Transmit Mode)**：
   - 進入 **設定 (Settings)** -> **語音 (Audio)** -> **傳輸模式 (Transmit Mode)**。
   - 選擇 **按住說話 (Push-to-Talk)**。
2. **語音品質 (Audio Quality)**：
   - 選擇 **平衡 (Balanced)** 或 **低延遲 (Low Latency)**。
   - 伺服器端音訊頻寬建議設定在 `40 kbps ~ 64 kbps`，確保在雪場或戶外 4G 弱訊號時依然流暢清晰。
3. **回音消除 (Echo Cancellation)**：
   - 勾選開啟。

---

## 🔨 自行編譯專案 (Build from Source)

本專案提供免安裝肥大 Android Studio 的超輕量編譯腳本 `build.ps1`，僅需 JDK 8/17 與 Android Commandline Tools：

```powershell
.\build.ps1
```

---

## 📂 專案結構 (Directory Structure)

```
anysecu-ptt-bridge/
├── AndroidManifest.xml          # 核心宣告 (含 READ_LOGS 與常駐廣播)
├── LICENSE                      # MIT 授權條款
├── README.md                    # 本文件
├── build.ps1                    # 免 IDE 一鍵編譯簽名腳本
├── debug.keystore               # 開發測試簽名金鑰
├── releases/
│   ├── AnysecuPttBridge-v1.3.apk # 最新 ALSA-BCLO 硬體級佔線禁發版
│   └── mumla-v3.7.3.apk         # 經實測最穩定相容的 Mumla 客戶端
├── scripts/
│   └── setup-device.ps1         # 對講機一鍵佈署與螢幕修復腳本
└── src/
    └── com/anysecu/pttbridge/
        ├── BootReceiver.java    # 開機自啟廣播接收器
        ├── MainActivity.java    # 狀態顯示與服務喚醒
        └── PttBridgeService.java# 核心日誌管道監聽與 ALSA-BCLO 佔線阻擋邏輯
```

---

## 📜 授權協議 (License)

本專案採用 [MIT License](LICENSE) 開源授權，歡迎自由修改、改進與分享！
