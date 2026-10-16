package io.github.workbuddymonitor.ds;

import android.content.Context;

/**
 * DeepSeek 子系统的小工具。
 *
 * 说明：DeepSeek 不再有独立的小组件 —— 按要求，只在**原有 WorkBuddy 小组件**底部
 * 多加一行「DeepSeek 余额 · 今日消耗」，由 io.github.workbuddymonitor.WbWidget#setDeepSeekLine 渲染。
 * 所以这里只保留共用的过期判定与时间格式化。
 */
public final class DsWidgets {

    private DsWidgets() {}

    /**
     * 数据是否过期：超过 max(3×采样间隔, 45 分钟) 即判过期。
     * 过期数据绝不冒充实时值（历史事故：界面长期显示旧数字，用户据此认为监控不可信）。
     */
    public static boolean isStale(Context c, Stats st, DsPrefs p) {
        if (!st.hasData) return true;
        long max = Math.max(p.getIntervalMin() * 60000L * 3L, 45 * 60000L);
        return System.currentTimeMillis() - st.lastTs > max;
    }

    /** "刚刚更新 / N 分钟前 / N 小时前 / N 天前" */
    public static String ageText(long ts) {
        if (ts <= 0) return "尚未更新";
        long d = System.currentTimeMillis() - ts;
        if (d < 0) d = 0;
        if (d < 60_000L) return "刚刚更新";
        if (d < 3_600_000L) return (d / 60_000L) + " 分钟前";
        if (d < 86_400_000L) return (d / 3_600_000L) + " 小时前";
        return (d / 86_400_000L) + " 天前";
    }
}
