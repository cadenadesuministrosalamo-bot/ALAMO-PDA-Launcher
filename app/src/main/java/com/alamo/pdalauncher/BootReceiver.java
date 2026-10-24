package com.alamo.pdalauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    public static final String ACTION_RESTART_MONITOR = "com.alamo.pdalauncher.RESTART_MONITOR";
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(action) || ACTION_RESTART_MONITOR.equals(action)) && RemoteStore.isRegistered(context)) {
            RemoteMonitorService.start(context);
        }
    }
}
