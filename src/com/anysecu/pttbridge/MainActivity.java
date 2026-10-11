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
        tv.setText("Anysecu PTT Bridge v1.4\n\n狀態: 服務常駐中\n功能 1: 硬體 ALSA-BCLO 佔線禁發\n功能 2: 口袋防誤觸鎖定\n  • 長按音量下鍵: 滅屏並鎖定正面按鍵\n  • 連按音量下鍵 5 次: 點亮螢幕並解鎖\n  • PTT 鍵隨時可正常發話");
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(20, 36, 20, 36);
        setContentView(tv);
    }
}
