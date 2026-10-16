package io.github.workbuddymonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.workbuddymonitor.ds.DsRefreshTask;
import io.github.workbuddymonitor.ds.DsScheduler;
import io.github.workbuddymonitor.ds.DsWidgets;

public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        Scheduler.schedule(c);
        WbWidget.pushAll(c);
        // DeepSeek 是独立的一条刷新链，开机 / 覆盖安装后同样必须重新续期，
        // 否则它的定时采样与小组件会永久停在最后一次（宿主踩过的历史事故）。
        try {
            DsScheduler.schedule(c);
            WbWidget.renderAllFromCache(c);
        } catch (Throwable t) {
            android.util.Log.w("wbmon", "ds boot schedule skipped: " + t, t);
        }
    }
}
