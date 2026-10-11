package com.anysecu.pttbridge;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class PocketLockAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "PocketLockAdminReceiver";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device Admin successfully ENABLED for Pocket Lock Mode");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(TAG, "Device Admin DISABLED");
    }
}
