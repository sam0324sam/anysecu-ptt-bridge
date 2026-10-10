package com.anysecu.pttbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.view.Gravity;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent serviceIntent = new Intent(this, PttBridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        TextView tv = new TextView(this);
        tv.setText("Anysecu PTT Bridge v1.1\n\n狀態: 守護服務已啟動\n功能: PTT 轉發 + 佔線禁發 (BCLO)\n開機將自動常駐運作\n免電腦免維護");
        tv.setTextSize(17);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(24, 48, 24, 48);
        setContentView(tv);
    }
}
