package io.github.workbuddymonitor.ds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * DeepSeek 定时采样与手动刷新入口。
 *
 * 使用 goAsync() 把广播的生命周期延长到网络请求结束（约 10 秒预算，余额接口通常 &lt;2 秒），
 * 从而避免 Android 8+ 的后台 Service 限制，也不用常驻前台服务。
 */
public class DsTickReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "io.github.workbuddymonitor.ds.TICK";
    public static final String ACTION_REFRESH = "io.github.workbuddymonitor.ds.REFRESH";

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        DsRefreshTask.run(app, new DsRefreshTask.Callback() {
            @Override public void done(boolean ok, String error, Stats stats) {
                try { pr.finish(); } catch (Exception ignored) {}
            }
        });
    }
}
