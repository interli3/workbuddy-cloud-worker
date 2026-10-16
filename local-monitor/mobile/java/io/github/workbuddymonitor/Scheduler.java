package io.github.workbuddymonitor;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Build;

public class Scheduler {

    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long interval = Math.max(Store.interval(c), 15_000L);
        long at = System.currentTimeMillis() + interval;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, SettingsActivity.pi(c));
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, SettingsActivity.pi(c));
            }
        } catch (SecurityException e) {
            // 无精确闹钟权限时退化为非精确周期
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, at, Math.max(interval, 600_000L), SettingsActivity.pi(c));
        }
    }
}
