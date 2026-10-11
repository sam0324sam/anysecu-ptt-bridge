package com.anysecu.pttbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;

public class PttBridgeService extends Service {
    private static final String TAG = "AnysecuPttBridge";
    private static final String CHANNEL_ID = "ptt_bridge_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String MUMLA_PKG = "se.lublin.mumla";
    private static final String MUMLA_ACTION = "se.lublin.mumla.action.TALK";
    private static final long HANGOVER_TIME_MS = 200; // 防搶尾音與斷句緩衝 (200ms)
    private static final long OK_LONG_PRESS_MS = 4800; // 長按 OK 鍵滿 4.8 秒 (~5 秒) 觸發口袋鎖定

    private static final String[] PCM_STATUS_PATHS = new String[] {
        "/proc/asound/card0/pcm0p/sub0/status",
        "/proc/asound/card0/pcm1p/sub0/status",
        "/proc/asound/card0/pcm2p/sub0/status",
        "/proc/asound/card0/pcm3p/sub0/status",
        "/proc/asound/card0/pcm4p/sub0/status",
        "/proc/asound/card0/pcm5p/sub0/status",
        "/proc/asound/card0/pcm7p/sub0/status",
        "/proc/asound/card0/pcm10p/sub0/status",
        "/proc/asound/card0/pcm12p/sub0/status",
        "/proc/asound/card0/pcm13p/sub0/status",
        "/proc/asound/card0/pcm14p/sub0/status",
        "/proc/asound/card0/pcm15p/sub0/status",
        "/proc/asound/card0/pcm16p/sub0/status",
        "/proc/asound/card0/pcm17p/sub0/status",
        "/proc/asound/card0/pcm18p/sub0/status",
        "/proc/asound/card0/pcm25p/sub0/status",
        "/proc/asound/card0/pcm34p/sub0/status",
        "/proc/asound/card0/pcm35p/sub0/status",
        "/proc/asound/card0/pcm36p/sub0/status",
        "/proc/asound/card0/pcm37p/sub0/status"
    };

    private BroadcastReceiver mPttReceiver;
    private Thread mLogcatThread;
    private volatile boolean mRunning = true;
    private Process mLogcatProcess;

    private volatile boolean mIsTalking = false;
    private volatile boolean mBlocked = false;
    private volatile long mLastPlaybackActiveTime = 0;
    private volatile long mLastBusyToneTime = 0;
    private ToneGenerator mToneGenerator;

    // OK 鍵長按計時追蹤
    private volatile long mOkKeyDownStartTime = 0;
    private volatile long mLastOkEventTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        try {
            mToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM, 90);
        } catch (Exception ignored) {}
        registerPttReceiver();
        startLogcatListener();
        Log.i(TAG, "PttBridgeService v1.4 started (Hardware ALSA-BCLO + Pocket Lock Engine)");
    }

    /**
     * 透過 Linux ALSA 硬體狀態檢查喇叭是否正在發聲 (隊友講話中)
     */
    private boolean isAlsaPlaybackActive() {
        byte[] buffer = new byte[32];
        for (String path : PCM_STATUS_PATHS) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(path);
                int n = fis.read(buffer);
                if (n > 0) {
                    // closed 首字元為 'c'，直接跳過；若非 'c' 則檢查是否為 RUNNING
                    if (buffer[0] != 'c') {
                        String s = new String(buffer, 0, n);
                        if (s.contains("RUNNING")) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (fis != null) {
                    try { fis.close(); } catch (Exception ignored) {}
                }
            }
        }
        return false;
    }

    /**
     * 判定頻道是否佔線 (BCLO)
     */
    private boolean isChannelBusy() {
        // 若距上次自身忙線音小於 120ms，忽略自身聲音避免回饋
        if (System.currentTimeMillis() - mLastBusyToneTime < 120) {
            return false;
        }

        boolean activeNow = isAlsaPlaybackActive();
        if (activeNow) {
            mLastPlaybackActiveTime = System.currentTimeMillis();
            return true;
        }

        // 檢查防搶尾音緩衝
        long elapsed = System.currentTimeMillis() - mLastPlaybackActiveTime;
        return elapsed < HANGOVER_TIME_MS;
    }

    private void playBusyTone() {
        mLastBusyToneTime = System.currentTimeMillis();
        try {
            if (mToneGenerator != null) {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 80);
            }
        } catch (Exception ignored) {}
    }

    private synchronized void handlePttDown() {
        if (isChannelBusy()) {
            // 隊友說話中 -> 佔線禁發 (BCLO 阻擋)
            mBlocked = true;
            Log.w(TAG, "Channel Busy (Teammate Talking) -> Block PTT & Sound Beep");
            playBusyTone();
            return;
        }

        mBlocked = false;
        if (!mIsTalking) {
            mIsTalking = true;
            Log.d(TAG, "Channel Free -> PTT Down -> Mumla Talk ON");
            sendMumlaTalk("on");
        }
    }

    private synchronized void handlePttUp() {
        if (mBlocked) {
            mBlocked = false;
            return;
        }

        if (mIsTalking) {
            mIsTalking = false;
            Log.d(TAG, "PTT Up -> Mumla Talk OFF");
            sendMumlaTalk("off");
        }
    }

    private synchronized void handleOkKeyDown() {
        if (PocketLockActivity.isLocked()) {
            mOkKeyDownStartTime = 0;
            return;
        }

        long now = System.currentTimeMillis();
        // 若兩次事件間隔超過 400ms，視為新的一輪按壓
        if (now - mLastOkEventTime > 400 || mOkKeyDownStartTime == 0) {
            mOkKeyDownStartTime = now;
        }
        mLastOkEventTime = now;

        // 檢查是否連續按住達 4.8 秒 (~5 秒)
        if (mOkKeyDownStartTime > 0 && (now - mOkKeyDownStartTime) >= OK_LONG_PRESS_MS) {
            mOkKeyDownStartTime = 0;
            triggerPocketLock();
        }
    }

    private synchronized void handleOkKeyUp() {
        mOkKeyDownStartTime = 0;
    }

    private void triggerPocketLock() {
        Log.i(TAG, "OK Key held 5s -> Triggering Pocket Lock Mode");
        try {
            if (mToneGenerator != null) {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
            }
        } catch (Exception ignored) {}

        Intent lockIntent = new Intent(this, PocketLockActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(lockIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PTT Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background PTT button bridge for Mumla with ALSA-BCLO & Pocket Lock");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Anysecu PTT Bridge")
               .setContentText("PTT 守護中 (含 ALSA-BCLO 與長按OK口袋鎖定)")
               .setSmallIcon(android.R.drawable.ic_btn_speak_now)
               .setContentIntent(pendingIntent)
               .setOngoing(true);

        return builder.build();
    }

    private void registerPttReceiver() {
        if (mPttReceiver != null) return;
        mPttReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();
                if ("unipro.hotkey.ptt.down".equals(action) || "android.intent.action.PTT.down".equals(action)) {
                    handlePttDown();
                } else if ("unipro.hotkey.ptt.up".equals(action) || "android.intent.action.PTT.up".equals(action)) {
                    handlePttUp();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("unipro.hotkey.ptt.down");
        filter.addAction("unipro.hotkey.ptt.up");
        filter.addAction("android.intent.action.PTT.down");
        filter.addAction("android.intent.action.PTT.up");
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(mPttReceiver, filter);
    }

    private void startLogcatListener() {
        mRunning = true;
        mLogcatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mRunning) {
                    try {
                        try {
                            Runtime.getRuntime().exec("logcat -b system -c").waitFor();
                        } catch (Exception ignored) {}

                        mLogcatProcess = Runtime.getRuntime().exec(new String[]{"logcat", "-b", "system", "-v", "raw", "-s", "WindowManager:D"});
                        BufferedReader reader = new BufferedReader(new InputStreamReader(mLogcatProcess.getInputStream()));
                        String line;
                        while (mRunning && (line = reader.readLine()) != null) {
                            if (line.contains("unipro.hotkey.ptt.down")) {
                                handlePttDown();
                            } else if (line.contains("unipro.hotkey.ptt.up")) {
                                handlePttUp();
                            } else if (line.contains("KEYCODE_DPAD_CENTER = false")) {
                                handleOkKeyUp();
                            } else if (line.contains("KEYCODE_DPAD_CENTER = true")) {
                                handleOkKeyDown();
                            }
                        }
                    } catch (Exception e) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    } finally {
                        if (mLogcatProcess != null) {
                            mLogcatProcess.destroy();
                            mLogcatProcess = null;
                        }
                    }
                }
            }
        });
        mLogcatThread.setPriority(Thread.MAX_PRIORITY);
        mLogcatThread.start();
    }

    private void sendMumlaTalk(String status) {
        Intent talkIntent = new Intent(MUMLA_ACTION);
        talkIntent.setPackage(MUMLA_PKG);
        talkIntent.putExtra("status", status);
        sendBroadcast(talkIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        if (mLogcatProcess != null) {
            mLogcatProcess.destroy();
            mLogcatProcess = null;
        }
        if (mLogcatThread != null) {
            mLogcatThread.interrupt();
            mLogcatThread = null;
        }
        if (mPttReceiver != null) {
            try {
                unregisterReceiver(mPttReceiver);
            } catch (Exception ignored) {}
            mPttReceiver = null;
        }
        if (mToneGenerator != null) {
            try {
                mToneGenerator.release();
            } catch (Exception ignored) {}
            mToneGenerator = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
