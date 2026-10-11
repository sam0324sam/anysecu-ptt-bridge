package com.anysecu.pttbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * 口袋純淨防誤觸鎖定層：
 * - 關閉畫面顯示 (全螢幕純黑)
 * - 亮度管理遵循原廠系統預設，不干涉休眠
 * - 100% 阻擋吞噬正面按鍵 (OK、返回、方向鍵) 與音量+鍵
 * - 連續點擊音量下鍵 5 次解鎖退出
 */
public class PocketLockActivity extends Activity {
    private static final String TAG = "PocketLockActivity";
    private static volatile PocketLockActivity sInstance;

    private int mUnlockClicks = 0;
    private long mLastClickTime = 0;
    private ToneGenerator mToneGenerator;

    public static boolean isLocked() {
        return sInstance != null && !sInstance.isFinishing();
    }

    public static void dismiss() {
        if (sInstance != null && !sInstance.isFinishing()) {
            sInstance.finish();
            sInstance = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;

        try {
            mToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM, 90);
        } catch (Exception ignored) {}

        // 綁定音量控制串流，防止音量鍵彈出系統面板或發出系統音效
        setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);

        // 全螢幕沉浸式純黑，關閉內容顯示 (亮度與休眠遵循系統原廠設定)
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = getWindow();
        win.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        View blackView = new View(this);
        blackView.setBackgroundColor(Color.BLACK);
        blackView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LOW_PROFILE
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(blackView);

        Log.i(TAG, "PocketLockActivity active (Display Blackout & Front Keys Blocked)");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 若系統關機選單或任何 System Dialog 嘗試搶奪焦點，立即強制關閉！
        if (!hasFocus && isLocked()) {
            try {
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            } catch (Exception ignored) {}
        }
    }

    private boolean isVolumeDownKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == 25;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // 1. 解鎖鍵：只響應「音量下鍵」的 ACTION_DOWN 按下事件，且 repeatCount 必須為 0
        if (isVolumeDownKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    onUnlockClick();
                }
            }
            // 嚴格吃掉音量下鍵的 DOWN 與 UP，絕不改變系統音量！
            return true;
        }

        // 2. 音量鍵+ (KEYCODE_VOLUME_UP 24)：
        // 徹底吞噬，嚴禁系統發出調整音量嗶嗶聲，嚴禁調整音量！
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == 24) {
            return true;
        }

        // 3. 返回鍵 (KEYCODE_BACK 4)：
        // 防止長按返回鍵彈出系統關機選單，發送廣播秒殺對話框！
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 4) {
            try {
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            } catch (Exception ignored) {}
            return true;
        }

        // 4. 正面其餘所有按鍵（方向鍵、OK鍵、菜單鍵等）：
        // 100% 徹底阻擋吞噬，不發出聲音，不傳給底層 Mumla！
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return true;
    }

    private synchronized void onUnlockClick() {
        long now = System.currentTimeMillis();

        // 200ms 防抖去重 (防止硬體彈跳抖動)
        if (now - mLastClickTime < 200) {
            return;
        }

        if (now - mLastClickTime < 900) {
            mUnlockClicks++;
        } else {
            mUnlockClicks = 1; // 間隔超過 900ms 重新計數
        }
        mLastClickTime = now;

        Log.d(TAG, "Volume-Down Click in lock mode: " + mUnlockClicks + "/5");

        // 口袋盲操短嗶音反饋 (每實體按下一次滴一聲)
        try {
            if (mToneGenerator != null) {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
            }
        } catch (Exception ignored) {}

        // 必須實打實連續按下滿 5 次！
        if (mUnlockClicks >= 5) {
            mUnlockClicks = 0;
            unlockAndFinish();
        }
    }

    private void unlockAndFinish() {
        Log.i(TAG, "Unlocked by 5 Volume-Down clicks! Finishing overlay.");
        try {
            if (mToneGenerator != null) {
                // 輕快兩聲確認音「嗶嗶」
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 250);
            }
        } catch (Exception ignored) {}

        finish();
        sInstance = null;
    }

    @Override
    public void onBackPressed() {
        // 嚴禁返回鍵退出
    }

    @Override
    protected void onDestroy() {
        if (mToneGenerator != null) {
            try { mToneGenerator.release(); } catch (Exception ignored) {}
            mToneGenerator = null;
        }
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }
}
