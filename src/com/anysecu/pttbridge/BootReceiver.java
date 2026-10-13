package com.anysecu.pttbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            "com.anysecu.pttbridge.action.RESTART_SERVICE".equals(action)) {

            Intent serviceIntent = new Intent(context, PttBridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            // 開機冷啟動護航：喚醒 Mumla 確保自動加入頻道
            if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
                try {
                    Intent mumlaLaunch = context.getPackageManager().getLaunchIntentForPackage("se.lublin.mumla");
                    if (mumlaLaunch != null) {
                        mumlaLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(mumlaLaunch);
                    }
                } catch (Exception ignored) {}
            }
        }
    }
}
