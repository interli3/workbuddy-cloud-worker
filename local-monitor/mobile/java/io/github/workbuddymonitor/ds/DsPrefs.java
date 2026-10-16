package io.github.workbuddymonitor.ds;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * DeepSeek 子系统的偏好存储。
 *
 * 关键隔离点：偏好文件名用 "wbmon_ds"，与宿主 WorkBuddy 的 "wbmon" 完全不同，
 * 因此两套配置永不互相覆盖（宿主升级时的 Store.migrate 只动 "wbmon"）。
 */
public final class DsPrefs {

    private static final String NAME = "wbmon_ds";
    private static final String K_KEY = "api_key";
    private static final String K_INTERVAL = "interval_min";
    private static final String K_WARN = "warn_threshold";
    private static final String K_NOTIFY = "notify_enabled";
    private static final String K_WARN_ON = "warn_enabled";
    private static final String K_CURRENCY = "currency";
    private static final String K_LAST_ERR = "last_error";
    private static final String K_LAST_OK = "last_ok_ts";
    private static final String K_TOTAL = "cache_total";
    private static final String K_GRANTED = "cache_granted";
    private static final String K_TOPPED = "cache_topped";

    private final SharedPreferences sp;

    public DsPrefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public String getKey() { return sp.getString(K_KEY, "").trim(); }
    public void setKey(String v) { sp.edit().putString(K_KEY, v == null ? "" : v.trim()).apply(); }
    public boolean hasKey() { return !getKey().isEmpty(); }

    public int getIntervalMin() { return sp.getInt(K_INTERVAL, 15); }
    public void setIntervalMin(int v) { sp.edit().putInt(K_INTERVAL, v).apply(); }

    public double getWarnThreshold() { return Double.longBitsToDouble(sp.getLong(K_WARN, Double.doubleToLongBits(10d))); }
    public void setWarnThreshold(double v) { sp.edit().putLong(K_WARN, Double.doubleToLongBits(v)).apply(); }

    public boolean isNotifyEnabled() { return sp.getBoolean(K_NOTIFY, true); }
    public void setNotifyEnabled(boolean v) { sp.edit().putBoolean(K_NOTIFY, v).apply(); }

    public boolean isWarnEnabled() { return sp.getBoolean(K_WARN_ON, true); }
    public void setWarnEnabled(boolean v) { sp.edit().putBoolean(K_WARN_ON, v).apply(); }

    public String getCurrency() { return sp.getString(K_CURRENCY, "CNY"); }
    public void setCurrency(String v) { sp.edit().putString(K_CURRENCY, v).apply(); }

    public String getLastError() { return sp.getString(K_LAST_ERR, ""); }
    public void setLastError(String v) { sp.edit().putString(K_LAST_ERR, v == null ? "" : v).apply(); }

    public long getLastOkTs() { return sp.getLong(K_LAST_OK, 0L); }
    public void setLastOkTs(long v) { sp.edit().putLong(K_LAST_OK, v).apply(); }

    public void cacheSnapshot(double total, double granted, double topped) {
        sp.edit().putLong(K_TOTAL, Double.doubleToLongBits(total))
                .putLong(K_GRANTED, Double.doubleToLongBits(granted))
                .putLong(K_TOPPED, Double.doubleToLongBits(topped)).apply();
    }
    public double cacheTotal() { return Double.longBitsToDouble(sp.getLong(K_TOTAL, Double.doubleToLongBits(0d))); }
    public double cacheGranted() { return Double.longBitsToDouble(sp.getLong(K_GRANTED, Double.doubleToLongBits(0d))); }
    public double cacheTopped() { return Double.longBitsToDouble(sp.getLong(K_TOPPED, Double.doubleToLongBits(0d))); }

    /** 余额跌破阈值只提醒一次，回升后才重新武装。 */
    public boolean isWarnArmed() { return sp.getBoolean("warn_armed", true); }
    public void setWarnArmed(boolean v) { sp.edit().putBoolean("warn_armed", v).apply(); }
}
