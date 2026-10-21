package com.alamo.pdalauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RemoteActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (RemoteMonitorService.ACTION_LOCAL_FOUND.equals(intent.getAction())) {
            RemoteMonitorService.markFound(context);
        }
    }
}
