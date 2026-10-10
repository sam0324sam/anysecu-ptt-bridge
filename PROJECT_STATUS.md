# Project Status & Decision Log: Anysecu PTT Bridge

## 1. 專案核心成果 (Milestone Achieved)
- **硬體對象**：Anysecu G393 實體按鍵對講機 (Qualcomm MSM8909, Android 8.1.0, 物理螢幕 128x160)。
- **核心目標**：擺脫原廠 Zello 綁定，全面介接自建開源 Mumla (Mumble) 伺服器，實現多人低延遲、高音質通話，並內建專業無線電必備的「佔線禁發 (BCLO)」與「防搶尾音 (Hangover Time)」功能。
- **最終驗證**：雙機均已實測驗證大成功，PTT 按住發話無延遲，隊友說話時 100% 精準阻擋送話並響起短促忙線嗶聲！

---

## 2. 關鍵架構演進與技術決策記錄 (Architecture Decision Records)

### ADR-01: 繞過底層按鍵攔截 (Logcat Memory Pipe)
- **問題**：Anysecu 原廠韌體中的 `PhoneWindowManager.interceptKeyBeforeQueueing` 攔截了硬體 F1 鍵 (KeyCode 131)，不對外發送公開的 BroadcastReceiver 廣播，導致市面所有 Key Remapper 工具失效。
- **決策**：授予 `READ_LOGS` 特殊權限，透過原生 Java Process 建立非阻塞記憶體管線，監聽 `logcat -b system -v raw -s WindowManager:D` 中之 `unipro.hotkey.ptt.down/up` 訊號。
- **優勢**：零耗電、0% 閒置 CPU 佔用、不寫入磁碟保護 Flash 晶片壽命。

### ADR-02: 突破 128x160 超小螢幕 UI 截斷問題 (Virtual Resolution Override)
- **問題**：物理螢幕 128x160 在 Mumla 伺服器編輯頁面中無法滾動至「使用者名稱」與「密碼」欄位。Android 系統限制 `wm density >= 72`，無法透過縮小 dpi 解決。
- **決策**：利用 ADB 注入虛擬解析度 `wm size 200x240` + `wm density 72`。
- **優勢**：成功擴增垂直視窗空間，使長表單得以完整顯示與滾動輸入。

### ADR-03: 拔除 Java AudioCallback，改採 Linux Kernel ALSA 硬體狀態檢測 (ALSA-BCLO Engine)
- **問題 (v1.1 死鎖事件)**：
  - Android 8.1 缺少 Android 10+ 的 `AudioPlaybackConfiguration.isActive()` API。
  - Mumla 進入房間後常態維持 AudioTrack 實例，導致上層 Java Callback 誤判「隨時有音訊在播放」。
  - 加上觸發忙線音 (`ToneGenerator`) 時產生的音訊，回饋給自己造成「自我激勵正回授死鎖」，使發話鍵永久卡死成只會嘟一聲。
- **決策 (v1.3 重構)**：
  - 徹底捨棄不穩定的 Java Audio Callback。
  - 直接在按鍵按下時，耗時 0.2ms 讀取 Linux ALSA 核心狀態 (`/proc/asound/card0/pcm*p/sub0/status`)。
  - 靜止時狀態必定為 `closed`（首字元 `'c'` 極速跳過）；隊友說話喇叭震動時狀態即時躍遷為 `RUNNING`。
- **優勢**：100% 物理硬體真實狀態反映，徹底杜絕死鎖，實現精準 BCLO 佔線禁發與 200ms 防搶尾音保護。

---

## 3. 部署與維護指引
- **專案倉庫**：[https://github.com/sam0324sam/anysecu-ptt-bridge](https://github.com/sam0324sam/anysecu-ptt-bridge)
- **建置工具**：本機執行 `.\build.ps1` 即可免 Android Studio 純命令列一鍵編譯簽名。
- **裝置設定**：新對講機接上 USB 執行 `.\scripts\setup-device.ps1` 即可全自動完成小螢幕修復、權限授予與軟體安裝。
