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
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
    private volatile long mLastLocalSoundEndTime = 0;
    private ToneGenerator mToneGenerator;

    private AudioManager mAudioManager;

    // 發話許可音 (Talk Permit Tone) 與 結束確認音 (Roger Beep)
    private SoundPool mSoundPool;
    private int mPermitSoundId = 0;
    private int mEndSoundId = 0;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStartTalkRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsTalking) {
                try {
                    if (mAudioManager != null) {
                        mAudioManager.setMicrophoneMute(false);
                    }
                } catch (Exception ignored) {}
                Log.d(TAG, "Permit tone finished & Mic unmuted -> Mumla Talk ON");
                sendMumlaTalk("on");
            }
        }
    };

    // 電量階梯語音報告
    private BroadcastReceiver mBatteryReceiver;
    private MediaPlayer mMediaPlayer;
    private int mLastAnnouncedThreshold = -1;
    private boolean mBatteryInitialized = false;
    private int mPendingBatteryResId = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            mToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM, 90);
        } catch (Exception ignored) {}

        // 初始化 SoundPool (低延遲短音效播放)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
                mSoundPool = new SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(attrs)
                    .build();
            } else {
                mSoundPool = new SoundPool(4, AudioManager.STREAM_MUSIC, 0);
            }
            mPermitSoundId = mSoundPool.load(this, R.raw.ptt_permit, 1);
            mEndSoundId = mSoundPool.load(this, R.raw.ptt_end, 1);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SoundPool: " + e.getMessage());
        }

        registerPttReceiver();
        registerBatteryReceiver();
        startLogcatListener();
        Log.i(TAG, "PttBridgeService v1.7 started (Hardware ALSA-BCLO + Pure Pocket Lock + Battery/Time Reports + Permit Tone & Roger Beep)");
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
            // 若當前聲音屬於本機播放之短提示音 (發話許可音/結束確認音/報時/電量)，非隊友遠端說話，不誤判為佔線
            if (System.currentTimeMillis() < mLastLocalSoundEndTime) {
                return false;
            }
            mLastPlaybackActiveTime = System.currentTimeMillis();
            return true;
        }

        // 檢查防搶尾音緩衝
        long elapsed = System.currentTimeMillis() - mLastPlaybackActiveTime;
        return elapsed < HANGOVER_TIME_MS;
    }

    private void playBusyTone() {
        mLastBusyToneTime = System.currentTimeMillis();
        mLastLocalSoundEndTime = System.currentTimeMillis() + 120;
        try {
            if (mToneGenerator != null) {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 80);
            }
        } catch (Exception ignored) {}
    }

    private void playPermitTone() {
        mLastLocalSoundEndTime = System.currentTimeMillis() + 80;
        if (mSoundPool != null && mPermitSoundId != 0) {
            try {
                // 調降音量至適中舒適度 (50% 柔和反饋)
                mSoundPool.play(mPermitSoundId, 0.45f, 0.45f, 1, 0, 1.0f);
            } catch (Exception e) {
                Log.e(TAG, "playPermitTone error: " + e.getMessage());
            }
        }
    }

    private void playEndTone() {
        mLastLocalSoundEndTime = System.currentTimeMillis() + 60;
        if (mSoundPool != null && mEndSoundId != 0) {
            try {
                // 調降音量至適中舒適度 (50% 柔和反饋)
                mSoundPool.play(mEndSoundId, 0.45f, 0.45f, 1, 0, 1.0f);
            } catch (Exception e) {
                Log.e(TAG, "playEndTone error: " + e.getMessage());
            }
        }
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
            Log.d(TAG, "Channel Free -> PTT Down -> Play Talk Permit Tone (方案 A)");

            // 1. 立即將全域麥克風硬體靜音，徹底防止揚聲器短音被麥克風捕捉進環形緩衝區
            try {
                if (mAudioManager != null) {
                    mAudioManager.setMicrophoneMute(true);
                }
            } catch (Exception ignored) {}

            // 2. 本地播放柔和許可音 (~38ms)
            playPermitTone();

            // 3. 許可音播完 + 揚聲器消振 + 緩衝清空 (180ms) 後，解除麥克風靜音並向 Mumla 開啟通話
            mMainHandler.removeCallbacks(mStartTalkRunnable);
            mMainHandler.postDelayed(mStartTalkRunnable, 180);
        }
    }

    private synchronized void handlePttUp() {
        if (mBlocked) {
            mBlocked = false;
            return;
        }

        mMainHandler.removeCallbacks(mStartTalkRunnable);

        if (mIsTalking) {
            mIsTalking = false;
            Log.d(TAG, "PTT Up -> Mumla Talk OFF -> Play Local Roger Beep (方案 A)");

            // 1. 立即停止 Mumla 麥克風音訊串流傳送
            sendMumlaTalk("off");

            // 2. 結束確認音播放期間短暫硬體靜音，防止尾音反饋
            try {
                if (mAudioManager != null) {
                    mAudioManager.setMicrophoneMute(true);
                }
            } catch (Exception ignored) {}

            playEndTone();

            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mAudioManager != null) {
                            mAudioManager.setMicrophoneMute(false);
                        }
                    } catch (Exception ignored) {}
                }
            }, 80);

            // 若在通話期間有累積待播報的電量語音，通話結束後 350ms 播報
            if (mPendingBatteryResId != 0) {
                final int resId = mPendingBatteryResId;
                mPendingBatteryResId = 0;
                mMainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        playBatteryVoice(resId);
                    }
                }, 350);
            }
        } else {
            // 防禦性確保麥克風處於開啟狀態
            try {
                if (mAudioManager != null) {
                    mAudioManager.setMicrophoneMute(false);
                }
            } catch (Exception ignored) {}
        }
    }

    private synchronized void triggerPocketLock() {
        if (PocketLockActivity.isLocked()) {
            return;
        }
        Log.i(TAG, "F4 / Volume-Down Long Press -> Triggering Pocket Lock Mode");
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
               .setContentText("PTT 守護中 (BCLO + 口袋鎖定 + 報時 + 許可/結束音)")
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
                } else if ("com.anysecu.pttbridge.action.ANNOUNCE_TIME".equals(action)) {
                    announceCurrentTime();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("unipro.hotkey.ptt.down");
        filter.addAction("unipro.hotkey.ptt.up");
        filter.addAction("android.intent.action.PTT.down");
        filter.addAction("android.intent.action.PTT.up");
        filter.addAction("com.anysecu.pttbridge.action.ANNOUNCE_TIME");
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
                            Runtime.getRuntime().exec("logcat -b system -b main -c").waitFor();
                        } catch (Exception ignored) {}

                        mLogcatProcess = Runtime.getRuntime().exec(new String[]{"logcat", "-b", "system", "-b", "main", "-v", "raw", "-s", "WindowManager:D"});
                        BufferedReader reader = new BufferedReader(new InputStreamReader(mLogcatProcess.getInputStream()));
                        String line;
                        while (mRunning && (line = reader.readLine()) != null) {
                            if (line.contains("unipro.hotkey.ptt.down")) {
                                handlePttDown();
                            } else if (line.contains("unipro.hotkey.ptt.up")) {
                                handlePttUp();
                            } else if (line.contains("MSG_KEY_F4_LONG_PRESS_TIMEOUT")) {
                                // 僅由長按音量下鍵觸發進入鎖定 (鎖定中 triggerPocketLock 自動忽略)
                                triggerPocketLock();
                            } else if (PocketLockActivity.isLocked() && line.contains("keycode=24 ")) {
                                // 口袋鎖定模式下 (無論螢幕是否休眠全暗)，音量+ (keycode 24) 即時觸發語音報時
                                // 嚴格排除原廠 F4/音量下鍵的 unipro.hotkey.p3.down
                                Log.i(TAG, "Volume+ detected while locked -> Announce time directly");
                                announceCurrentTime();
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

    private void registerBatteryReceiver() {
        if (mBatteryReceiver != null) return;
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;

                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                      status == BatteryManager.BATTERY_STATUS_FULL);

                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (scale <= 0) scale = 100;
                int percent = (int) ((level / (float) scale) * 100);

                handleBatteryUpdate(percent, isCharging);
            }
        };
        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void handleBatteryUpdate(int percent, boolean isCharging) {
        final int[] THRESHOLDS = { 80, 60, 40, 20, 10, 5 };

        if (!mBatteryInitialized) {
            mBatteryInitialized = true;
            // 開機/服務啟動時初始化基準門檻，避免開機直接誤播已經過去的門檻
            for (int t : THRESHOLDS) {
                if (percent <= t) {
                    mLastAnnouncedThreshold = t;
                }
            }
            Log.i(TAG, "Battery monitor initialized: " + percent + "%, charging=" + isCharging + ", baseThreshold=" + mLastAnnouncedThreshold);
            return;
        }

        if (isCharging) {
            // 充電時回充：若電量回升超過前一個門檻 + 3%，重置已播報門檻，以利下次放電再次觸發
            if (mLastAnnouncedThreshold != -1 && percent > mLastAnnouncedThreshold + 3) {
                mLastAnnouncedThreshold = -1;
            }
            mPendingBatteryResId = 0;
            return;
        }

        // 非充電放電中：檢查是否剛好跌破門檻 (80, 60, 40, 20, 10, 5)
        for (int t : THRESHOLDS) {
            if (percent <= t && (mLastAnnouncedThreshold == -1 || mLastAnnouncedThreshold > t)) {
                mLastAnnouncedThreshold = t;
                int resId = getBatteryAudioRes(t);
                if (mIsTalking) {
                    mPendingBatteryResId = resId;
                    Log.i(TAG, "Battery threshold " + t + "% hit during PTT, deferred.");
                } else {
                    Log.i(TAG, "Battery threshold " + t + "% hit (" + percent + "%), announcing now.");
                    playBatteryVoice(resId);
                }
                break;
            }
        }
    }

    private int getBatteryAudioRes(int threshold) {
        switch (threshold) {
            case 80: return R.raw.battery_80;
            case 60: return R.raw.battery_60;
            case 40: return R.raw.battery_40;
            case 20: return R.raw.battery_20;
            case 10: return R.raw.battery_10;
            case 5:  return R.raw.battery_5;
            default: return 0;
        }
    }

    private synchronized void playBatteryVoice(int resId) {
        if (resId == 0) return;
        try {
            if (mMediaPlayer != null) {
                try {
                    if (mMediaPlayer.isPlaying()) {
                        mMediaPlayer.stop();
                    }
                    mMediaPlayer.release();
                } catch (Exception ignored) {}
                mMediaPlayer = null;
            }
            mMediaPlayer = MediaPlayer.create(this, resId);
            if (mMediaPlayer != null) {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        try { mp.release(); } catch (Exception ignored) {}
                        if (mMediaPlayer == mp) {
                            mMediaPlayer = null;
                        }
                    }
                });
                mMediaPlayer.start();
                mLastLocalSoundEndTime = System.currentTimeMillis() + 1500;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to play battery voice: " + e.getMessage());
        }
    }

    private volatile long mLastTimeAnnounceTimestamp = 0;

    private synchronized void announceCurrentTime() {
        if (mIsTalking) {
            Log.d(TAG, "Ignore time announcement because PTT is active");
            return;
        }

        long now = System.currentTimeMillis();
        if (now - mLastTimeAnnounceTimestamp < 3000) {
            Log.d(TAG, "Ignore time announcement within debounce window (" + (now - mLastTimeAnnounceTimestamp) + "ms)");
            return;
        }
        mLastTimeAnnounceTimestamp = now;

        // 喚醒 CPU Partial WakeLock (3500ms)，確保螢幕休眠全暗狀態下語音播報完整不破音
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AnysecuPttBridge:TimeAnnounce");
                wl.acquire(3500);
            }
        } catch (Exception ignored) {}

        try {
            // 完全遵循對講機系統當前時間與時區 (不覆蓋時區)
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int hour24 = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int minute = cal.get(java.util.Calendar.MINUTE);

            int hour12 = hour24 % 12;
            if (hour12 == 0) {
                hour12 = 12;
            }

            int hourResId = getResources().getIdentifier("hour_" + hour12, "raw", getPackageName());
            int minResId = getResources().getIdentifier("min_" + minute, "raw", getPackageName());

            if (hourResId == 0 || minResId == 0) {
                Log.e(TAG, "Missing audio res for hour=" + hour12 + " min=" + minute);
                return;
            }

            Log.i(TAG, "Announcing concise time: 24h=" + hour24 + " -> " + hour12 + "點 " + minute + "分");

            // 極簡報時：直接播報 [小時] + [分鐘]，不說現在時間、不說早上晚上
            playSynthesizedWav(new int[]{ hourResId, minResId });
        } catch (Exception e) {
            Log.e(TAG, "Failed to announce time: " + e.getMessage(), e);
        }
    }

    private synchronized void playSynthesizedWav(int[] resIds) {
        try {
            java.io.ByteArrayOutputStream pcmStream = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];

            for (int resId : resIds) {
                java.io.InputStream is = getResources().openRawResource(resId);
                java.io.ByteArrayOutputStream rawStream = new java.io.ByteArrayOutputStream();
                int read;
                while ((read = is.read(buf)) != -1) {
                    rawStream.write(buf, 0, read);
                }
                is.close();

                byte[] rawBytes = rawStream.toByteArray();
                appendPcmData(rawBytes, pcmStream);
            }

            byte[] allPcm = pcmStream.toByteArray();
            int totalPcm = allPcm.length;

            java.io.File cacheFile = new java.io.File(getCacheDir(), "current_time.wav");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
            java.io.DataOutputStream dos = new java.io.DataOutputStream(fos);

            // WAV Header (44 bytes, 22050Hz, 16bit, Mono)
            dos.writeBytes("RIFF");
            dos.writeInt(Integer.reverseBytes(36 + totalPcm));
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt ");
            dos.writeInt(Integer.reverseBytes(16)); // Subchunk1Size
            dos.writeShort(Short.reverseBytes((short) 1)); // AudioFormat = 1 (PCM)
            dos.writeShort(Short.reverseBytes((short) 1)); // NumChannels = 1 (Mono)
            dos.writeInt(Integer.reverseBytes(22050)); // SampleRate
            dos.writeInt(Integer.reverseBytes(44100)); // ByteRate (22050 * 1 * 2)
            dos.writeShort(Short.reverseBytes((short) 2)); // BlockAlign
            dos.writeShort(Short.reverseBytes((short) 16)); // BitsPerSample
            dos.writeBytes("data");
            dos.writeInt(Integer.reverseBytes(totalPcm));
            dos.write(allPcm);
            dos.flush();
            dos.close();
            fos.close();

            if (mMediaPlayer != null) {
                try {
                    if (mMediaPlayer.isPlaying()) {
                        mMediaPlayer.stop();
                    }
                    mMediaPlayer.release();
                } catch (Exception ignored) {}
                mMediaPlayer = null;
            }

            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.setDataSource(cacheFile.getAbsolutePath());
            mMediaPlayer.prepare();
            mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    try { mp.release(); } catch (Exception ignored) {}
                    if (mMediaPlayer == mp) {
                        mMediaPlayer = null;
                    }
                }
            });
            mMediaPlayer.start();
            mLastLocalSoundEndTime = System.currentTimeMillis() + 2500;
        } catch (Exception e) {
            Log.e(TAG, "Error synthesizing or playing time WAV: " + e.getMessage(), e);
        }
    }

    private void appendPcmData(byte[] rawWav, java.io.ByteArrayOutputStream out) {
        for (int i = 12; i < rawWav.length - 8; i++) {
            if (rawWav[i] == 0x64 && rawWav[i + 1] == 0x61 && rawWav[i + 2] == 0x74 && rawWav[i + 3] == 0x61) {
                int dataSize = (rawWav[i + 4] & 0xFF) |
                               ((rawWav[i + 5] & 0xFF) << 8) |
                               ((rawWav[i + 6] & 0xFF) << 16) |
                               ((rawWav[i + 7] & 0xFF) << 24);
                int start = i + 8;
                int len = Math.min(dataSize, rawWav.length - start);
                if (len > 0) {
                    out.write(rawWav, start, len);
                }
                return;
            }
        }
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
        if (mBatteryReceiver != null) {
            try {
                unregisterReceiver(mBatteryReceiver);
            } catch (Exception ignored) {}
            mBatteryReceiver = null;
        }
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.release();
            } catch (Exception ignored) {}
            mMediaPlayer = null;
        }
        if (mToneGenerator != null) {
            try {
                mToneGenerator.release();
            } catch (Exception ignored) {}
            mToneGenerator = null;
        }
        if (mSoundPool != null) {
            try {
                mSoundPool.release();
            } catch (Exception ignored) {}
            mSoundPool = null;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        if (mAudioManager != null) {
            try {
                mAudioManager.setMicrophoneMute(false);
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
