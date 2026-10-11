package com.anysecu.pttbridge;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class PocketLockActivity extends Activity {
    private static final String TAG = "PocketLockActivity";
    private static volatile PocketLockActivity sInstance;

    private int mUnlockClicks = 0;
    private long mLastClickTime = 0;
    private ToneGenerator mToneGenerator;

    public static boolean isLocked() {
        return sInstance != null && !sInstance.isFinishing();
    }

    public static synchronized void registerUnlockClick() {
        if (sInstance != null && !sInstance.isFinishing()) {
            sInstance.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (sInstance != null && !sInstance.isFinishing()) {
                        sInstance.onUnlockClick();
                    }
                }
            });
        }
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

        // 全螢幕純黑、沉浸式、最低背光亮度 (如同關閉螢幕)
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = getWindow();
        win.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        WindowManager.LayoutParams lp = win.getAttributes();
        lp.screenBrightness = 0.001f; // 最低背光亮度 (全黑)
        win.setAttributes(lp);

        View blackView = new View(this);
        blackView.setBackgroundColor(Color.BLACK);
        blackView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LOW_PROFILE
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(blackView);

        Log.i(TAG, "PocketLockActivity overlay active (Screen Blackout & Keys Blocked)");
    }

    private boolean isUnlockKey(int keyCode) {
        // 音量下鍵 (KEYCODE_VOLUME_DOWN 25, F4 134) 或 OK 鍵 (23, 66)
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            || keyCode == 25
            || keyCode == KeyEvent.KEYCODE_F4
            || keyCode == 134
            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == 23
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == 66;
    }

    public synchronized void onUnlockClick() {
        long now = System.currentTimeMillis();

        // 100ms 去重防抖（防止 dispatchKeyEvent 與 logcat 同步收到時重複計數）
        if (now - mLastClickTime < 100) {
            return;
        }

        if (now - mLastClickTime < 800) {
            mUnlockClicks++;
        } else {
            mUnlockClicks = 1;
        }
        mLastClickTime = now;

        Log.d(TAG, "Unlock Click in lock mode: " + mUnlockClicks + "/5");

        // 按鍵回饋音 (短嗶一聲)
        try {
            if (mToneGenerator != null) {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 35);
            }
        } catch (Exception ignored) {}

        // 連續點擊滿 5 下 -> 解鎖！
        if (mUnlockClicks >= 5) {
            mUnlockClicks = 0;
            unlockAndFinish();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // 1. 如果按下的是解鎖鍵 (音量下鍵 / OK 鍵)：檢測連點 5 下解鎖
        if (isUnlockKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN) {
                onUnlockClick();
            }
            return true; // 吃掉按鍵，不改變音量，不傳給底層
        }

        // 2. 正面其餘所有按鍵（返回、方向、功能鍵等）全部 100% 徹底阻擋吞噬！
        return true;
    }

    private void unlockAndFinish() {
        Log.i(TAG, "Unlocked by 5 clicks! Restoring brightness and finishing overlay.");
        try {
            if (mToneGenerator != null) {
                // 輕快兩聲確認音「嗶嗶」
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 250);
            }
        } catch (Exception ignored) {}

        // 恢復正常亮度
        try {
            Window win = getWindow();
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            win.setAttributes(lp);
        } catch (Exception ignored) {}

        finish();
        sInstance = null;
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
