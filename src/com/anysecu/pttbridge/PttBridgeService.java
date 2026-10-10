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
import android.media.AudioPlaybackConfiguration;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

public class PttBridgeService extends Service {
    private static final String CHANNEL_ID = "ptt_bridge_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String MUMLA_PKG = "se.lublin.mumla";
    private static final String MUMLA_ACTION = "se.lublin.mumla.action.TALK";
    private static final long HANGOVER_TIME_MS = 250; // 防搶尾音保護 250ms

    private BroadcastReceiver mPttReceiver;
    private Thread mLogcatThread;
    private volatile boolean mRunning = true;
    private Process mLogcatProcess;

    private AudioManager mAudioManager;
    private AudioManager.AudioPlaybackCallback mPlaybackCallback;
    private volatile boolean mAudioPlaying = false;
    private volatile long mLastAudioPlaybackTime = 0;
    private volatile boolean mTransmissionBlocked = false;
    private ToneGenerator mToneGenerator;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        initAudioManager();
        registerPttReceiver();
        startLogcatListener();
    }

    private void initAudioManager() {
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            mToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM, 90);
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mAudioManager != null) {
            mPlaybackCallback = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    super.onPlaybackConfigChanged(configs);
                    boolean playing = false;
                    if (configs != null) {
                        for (AudioPlaybackConfiguration config : configs) {
                            if (config != null && config.getAudioAttributes() != null) {
                                int usage = config.getAudioAttributes().getUsage();
                                if (usage != android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) {
                                    playing = true;
                                    break;
                                }
                            }
                        }
                    }
                    mAudioPlaying = playing;
                    if (playing) {
                        mLastAudioPlaybackTime = System.currentTimeMillis();
                    }
                }
            };
            mAudioManager.registerAudioPlaybackCallback(mPlaybackCallback, null);
        }
    }

    private boolean isChannelBusy() {
        if (mAudioPlaying) return true;
        if (mAudioManager != null && mAudioManager.isMusicActive()) return true;
        long elapsed = System.currentTimeMillis() - mLastAudioPlaybackTime;
        return elapsed < HANGOVER_TIME_MS;
    }

    private void playBusyTone() {
        try {
            if (mToneGenerator != null) {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 120);
            }
        } catch (Exception ignored) {}
    }

    private void handlePttDown() {
        if (isChannelBusy()) {
            playBusyTone();
            mTransmissionBlocked = true;
            return;
        }
        mTransmissionBlocked = false;
        sendMumlaTalk("on");
    }

    private void handlePttUp() {
        if (mTransmissionBlocked) {
            mTransmissionBlocked = false;
            return;
        }
        sendMumlaTalk("off");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PTT Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background PTT button bridge for Mumla with BCLO");
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
               .setContentText("PTT 守護中 (含佔線禁發 BCLO)")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mAudioManager != null && mPlaybackCallback != null) {
            try {
                mAudioManager.unregisterAudioPlaybackCallback(mPlaybackCallback);
            } catch (Exception ignored) {}
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
