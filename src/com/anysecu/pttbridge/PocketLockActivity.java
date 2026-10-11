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
        lp.screenBrightness = 0.001f; // 最低背光亮度
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

        Log.i(TAG, "PocketLockActivity overlay active (Screen Blackout & Front Keys Blocked)");
    }

    private boolean isOkKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == 23
            || keyCode == 66
            || keyCode == 353;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // 1. 如果按下的是 OK 鍵：檢測快速連點 5 下解鎖
        if (isOkKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - mLastClickTime < 800) {
                    mUnlockClicks++;
                } else {
                    mUnlockClicks = 1;
                }
                mLastClickTime = now;

                Log.d(TAG, "OK Click in lock mode: " + mUnlockClicks + "/5");

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
            return true; // 吃掉 OK 鍵，不傳遞給底層
        }

        // 2. 正面其餘所有按鍵（返回、方向、功能鍵等）全部 100% 徹底阻擋吞噬！
        return true;
    }

    private void unlockAndFinish() {
        Log.i(TAG, "Unlocked by 5 OK clicks! Finishing overlay.");
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
