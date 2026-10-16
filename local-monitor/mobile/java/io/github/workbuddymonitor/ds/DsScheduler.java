package io.github.workbuddymonitor.ds;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * DeepSeek 采样调度。
 *
 * 用 AlarmManager 自我重排（每次触发后重设下一次），而不是 setRepeating —— 后者在 Doze
 * 下会被整体推迟且无法在设置变更后立刻生效。API 31+ 若能拿到精确闹钟权限就用
 * setExactAndAllowWhileIdle，否则退回 setAndAllowWhileIdle（不精确但一定能到）。
 *
 * 与宿主 WorkBuddy 的 Scheduler 完全独立：requestCode 用 2001（宿主是 1001），
 * 目标组件也不同，两条刷新链互不干扰。
 */
public final class DsScheduler {

    public static final int REQ_TICK = 2001;

    private DsScheduler() {}

    public static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, DsTickReceiver.class);
        i.setAction(DsTickReceiver.ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ_TICK, i, flags);
    }

    public static void schedule(Context c) {
        schedule(c, new DsPrefs(c).getIntervalMin());
    }

    public static void schedule(Context c, int intervalMin) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tickIntent(c);
        long at = System.currentTimeMillis() + Math.max(1, intervalMin) * 60_000L;
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            exact = am.canScheduleExactAlarms();
        }
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(tickIntent(c));
    }

    /** 立即刷新一次（不改变下一次定时）。 */
    public static void refreshNow(Context c) {
        Intent i = new Intent(c, DsTickReceiver.class);
        i.setAction(DsTickReceiver.ACTION_REFRESH);
        i.setPackage(c.getPackageName());
        c.sendBroadcast(i);
    }
}
