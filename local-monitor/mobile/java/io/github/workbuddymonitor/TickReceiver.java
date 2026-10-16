package io.github.workbuddymonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TickReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        WbWidget.pushAll(c);
        Scheduler.schedule(c);
    }
}
