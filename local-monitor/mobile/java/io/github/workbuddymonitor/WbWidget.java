package io.github.workbuddymonitor;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.List;

public class WbWidget extends AppWidgetProvider {

    static final String ACTION_CLICK = "io.github.workbuddymonitor.CLICK";

    // 纯透明小组件的浅色文字板（与 colors.xml 的 w* 色一致）：
    // 白/浅灰文字 + 深色光晕（布局里 shadowColor），深浅壁纸都可读。
    // 注意：render() 每次刷新都会 setTextColor 覆盖 XML 静态色，两边必须同步改。
    private static final int C_MINT = 0xFF34D399;    // 亮薄荷（余额/进度/已签）
    private static final int C_PURPLE = 0xFFC4B5FD;  // 亮紫（运行中标记）
    private static final int C_WARN = 0xFFFBBF24;    // 亮琥珀（未签/临期）
    private static final int C_TEXT = 0xFFFFFFFF;    // 主文字（纯白）
    private static final int C_DIM = 0xFFC7D0DC;     // 次要文字（浅灰）
    private static final int C_FAINT = 0xFF9AA6B8;   // 弱化文字（中浅灰）
    private static final int C_DS = 0xFFB9C6FF;      // DeepSeek 那一行的蓝紫（与 WorkBuddy 的青蓝区分）

    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        // 任何意外都不应阻断系统把小组件添加到桌面（部分 OEM 桌面在 onUpdate 抛异常时会直接报「无法添加微件」）
        try {
            Store.migrate(c);
            for (int id : ids) render(c, m, id, readCache(c));
        } catch (Throwable t) {
            android.util.Log.w("wbmon", "widget onUpdate skipped: " + t, t);
        }
        // 关键修复：把刷新链的"启动"放在这里。
        // 此前只有 开机/覆盖安装/打开 App 才会 Scheduler.schedule()，
        // 于是"桌面上新拖一个小组件、又不重启不打开 App"时，30 秒刷新链从未启动，
        // 小组件会一直停在添加那一刻的快照上（表现为"明明在跑却显示无任务"）。
        try {
            Scheduler.schedule(c);
        } catch (Throwable t) {
            android.util.Log.w("wbmon", "widget schedule skipped: " + t, t);
        }
        refresh(c);
    }

    public void onEnabled(Context c) {
        try {
            Store.migrate(c);
            Scheduler.schedule(c);
        } catch (Throwable ignore) {
        }
        refresh(c);
    }

    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle b) {
        render(c, m, id, readCache(c));
    }

    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (i != null && ACTION_CLICK.equals(i.getAction())) {
            Intent launch = new Intent(c, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            c.startActivity(launch);
        }
    }

    static Snapshot readCache(Context c) {
        String s = Store.cache(c);
        if (s == null || s.length() == 0) return null;
        return Snapshot.fromCache(s);
    }

    public static void pushAll(final Context c) {
        refresh(c);
    }

    static void refresh(final Context c) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Fetcher.fetch(c, new Fetcher.Cb() {
                        public void onResult(Snapshot s, String err) {
                            try {
                                AppWidgetManager m = AppWidgetManager.getInstance(c);
                                int[] ids = m.getAppWidgetIds(new ComponentName(c, WbWidget.class));
                                for (int id : ids) render(c, m, id, s);
                            } catch (Throwable t) {
                                android.util.Log.w("wbmon", "widget push failed: " + t, t);
                            }
                        }
                    });
                } catch (Throwable t) {
                    android.util.Log.w("wbmon", "widget refresh failed: " + t, t);
                }
            }
        }).start();
    }

    /** 渲染兜底：任何一帧渲染失败都不能中断整条刷新链，否则小组件会永久停更。 */
    static void render(Context c, AppWidgetManager m, int id, Snapshot s) {
        try {
            renderInner(c, m, id, s);
        } catch (Throwable t) {
            android.util.Log.w("wbmon", "widget render failed: " + t, t);
        }
    }

    static void renderInner(Context c, AppWidgetManager m, int id, Snapshot s) {
        Bundle opt = m.getAppWidgetOptions(id);
        int w = 0, h = 0;
        if (opt != null) {
            w = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
            h = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        }
        boolean big = (w >= 220 || h >= 110);
        RemoteViews rv = new RemoteViews(c.getPackageName(), big ? R.layout.widget_medium2 : R.layout.widget_small2);

        if (s == null) {
            rv.setTextViewText(R.id.wBalance, "--");
            rv.setTextColor(R.id.wBalance, C_MINT);
            rv.setTextViewText(R.id.wUpdated, "--:--");
            rv.setTextViewText(R.id.wRunning, "");
            rv.setTextViewText(R.id.wBalanceHint, "未连接数据源");
            rv.setTextColor(R.id.wBalanceHint, C_WARN);
            rv.setTextViewText(R.id.wMonth, "打开 App 自动连接");
            rv.setTextColor(R.id.wMonth, C_DIM);
            rv.setTextViewText(R.id.wTaskDot, "\u00b7");
            rv.setTextColor(R.id.wTaskDot, C_DIM);
            rv.setTextViewText(R.id.wTaskLine, "暂无任务状态");
            rv.setTextColor(R.id.wTaskLine, C_DIM);
            rv.setTextViewText(R.id.wTaskPct, "");
            if (big) {
                rv.setTextViewText(R.id.wTask2, "");
                rv.setTextViewText(R.id.wAcc1, "");
                rv.setTextViewText(R.id.wAcc2, "");
                rv.setTextViewText(R.id.wSummary, "");
            }
        } else {
            // 数据是否过期：离线（走了缓存）或时间戳太旧，都不允许把旧数字当实时值展示。
            boolean stale = !s.online || s.isStale(Store.staleMs(c));
            String ver = "v" + Store.appVer(c);

            rv.setTextViewText(R.id.wBalance, MainActivity.fmtNum(s.totalBalance));
            rv.setTextColor(R.id.wBalance, stale ? C_WARN : C_MINT);

            // 运行中任务数：常显（空闲时显示"无运行任务"），过期时留空避免误导
            if (stale) {
                rv.setTextViewText(R.id.wRunning, "");
            } else if (s.running > 0) {
                rv.setTextViewText(R.id.wRunning, "\u25b6 " + s.running + " 运行中");
                rv.setTextColor(R.id.wRunning, C_PURPLE);
            } else {
                rv.setTextViewText(R.id.wRunning, "无运行任务");
                rv.setTextColor(R.id.wRunning, C_FAINT);
            }

            rv.setTextViewText(R.id.wBalanceHint,
                    s.activeCount + " 账号 · 已签 " + s.signedCount + "/" + s.activeCount);
            rv.setTextColor(R.id.wBalanceHint,
                    stale ? C_FAINT : (s.signedCount >= s.activeCount ? C_MINT : C_WARN));

            // 今日/本月消耗：数据过期时明确告知过期，而不是继续显示一个有零有整的旧数字
            if (stale) {
                rv.setTextViewText(R.id.wMonth,
                        "\u26a0 最后更新 " + MainActivity.fmtAgo(s.ageMs()) + " · 点我刷新");
                rv.setTextColor(R.id.wMonth, C_WARN);
            } else {
                rv.setTextViewText(R.id.wMonth, "今日 " + MainActivity.fmtNum(s.totalUsedToday)
                        + " · 本月 -" + MainActivity.fmtNum(s.usedMonth));
                rv.setTextColor(R.id.wMonth, s.totalUsedToday > 0 ? C_TEXT : C_DIM);
            }

            rv.setTextViewText(R.id.wUpdated, ver + " · " + MainActivity.fmtTime(s.ts));
            rv.setTextColor(R.id.wUpdated, stale ? C_WARN : C_DIM);

            // ---- 当前执行任务 ----
            List<Snapshot.Task> act = s.activeTasks();
            if (stale) {
                rv.setTextViewText(R.id.wTaskDot, "\u26a0");
                rv.setTextColor(R.id.wTaskDot, C_WARN);
                rv.setTextViewText(R.id.wTaskLine, "任务状态未知（数据未刷新）");
                rv.setTextColor(R.id.wTaskLine, C_WARN);
                rv.setTextViewText(R.id.wTaskPct, "");
                if (big) {
                    rv.setTextViewText(R.id.wTask2, "打开 App 或点一下这里即可刷新");
                    rv.setTextColor(R.id.wTask2, C_DIM);
                }
            } else if (act.size() > 0) {
                Snapshot.Task t = act.get(0);
                boolean running = "running".equals(t.state);
                rv.setTextViewText(R.id.wTaskDot, running ? "\u25b6" : "\u23f8");
                rv.setTextColor(R.id.wTaskDot, running ? C_MINT : C_WARN);
                rv.setTextViewText(R.id.wTaskLine, t.title.length() > 0 ? t.title : "(无标题任务)");
                rv.setTextColor(R.id.wTaskLine, C_TEXT);
                String pct = (t.progress > 0 ? t.progress : 5) + "%";
                rv.setTextViewText(R.id.wTaskPct, t.cost > 0 ? (pct + " · -" + MainActivity.fmtNum(t.cost)) : pct);
                rv.setTextColor(R.id.wTaskPct, running ? C_MINT : C_WARN);
                if (big) {
                    String sub = (running ? "运行中" : "等待输入") + " · " + t.meta;
                    if (act.size() > 1) sub += " · 另有 " + (act.size() - 1) + " 个";
                    rv.setTextViewText(R.id.wTask2, sub);
                    rv.setTextColor(R.id.wTask2, C_DIM);
                }
            } else {
                rv.setTextViewText(R.id.wTaskDot, "\u00b7");
                rv.setTextColor(R.id.wTaskDot, C_DIM);
                rv.setTextViewText(R.id.wTaskLine, "空闲 · 无执行中的任务");
                rv.setTextColor(R.id.wTaskLine, C_DIM);
                rv.setTextViewText(R.id.wTaskPct, "");
                if (big) {
                    int done = 0;
                    for (Snapshot.Task t : s.tasks) if ("done".equals(t.state)) done++;
                    String last = s.lastDoneTitle();
                    rv.setTextViewText(R.id.wTask2,
                            last.length() > 0 ? ("最近：" + last) : (done > 0 ? ("今日已完成 " + done + " 个任务") : ""));
                    rv.setTextColor(R.id.wTask2, C_DIM);
                }
            }

            if (big) {
                List<Snapshot.Account> accs = s.accounts;
                rv.setTextViewText(R.id.wAcc1, accs.size() > 0 ? accLine(accs.get(0)) : "");
                rv.setTextColor(R.id.wAcc1, stale ? C_FAINT : C_TEXT);
                rv.setTextViewText(R.id.wAcc2, accs.size() > 1 ? accLine(accs.get(1)) : "");
                rv.setTextColor(R.id.wAcc2, stale ? C_FAINT : C_TEXT);
                int health = healthOf(s);
                rv.setTextViewText(R.id.wSummary,
                        "累计 -" + MainActivity.fmtNum(s.totalUsed)
                                + (health >= 0 ? (" · 健康度 " + health) : ""));
                rv.setTextColor(R.id.wSummary, stale ? C_WARN : C_DIM);
            }
        }

        // ---- DeepSeek 余额：在原有内容下面加的一行 ----
        setDeepSeekLine(c, rv);

        Intent click = new Intent(c, WbWidget.class);
        click.setAction(ACTION_CLICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(c, id + 7, click, flags);
        rv.setOnClickPendingIntent(R.id.widgetRoot, pi);

        m.updateAppWidget(id, rv);
    }

    /**
     * DeepSeek 余额行（小组件底部那一行）。
     *
     * 数据来自手机直连的 DeepSeek 官方接口，与上面的 WorkBuddy 积分是两本账。
     * 这里只读本地缓存，不联网；采样由 DeepSeek 自己的闹钟节拍负责，采完会回调
     * {@link #renderAllFromCache} 立刻把这一行刷新出来。
     */
    private static void setDeepSeekLine(Context c, RemoteViews rv) {
        try {
            io.github.workbuddymonitor.ds.Stats st = io.github.workbuddymonitor.ds.DsRefreshTask.cached(c);
            io.github.workbuddymonitor.ds.DsPrefs p = new io.github.workbuddymonitor.ds.DsPrefs(c);
            if (!st.hasKey) {
                rv.setTextViewText(R.id.wDeepSeek, "DeepSeek 未配置 API Key");
                rv.setTextColor(R.id.wDeepSeek, C_FAINT);
            } else if (!st.hasData) {
                rv.setTextViewText(R.id.wDeepSeek, "DeepSeek 等待首次采样");
                rv.setTextColor(R.id.wDeepSeek, C_WARN);
            } else {
                boolean stale = io.github.workbuddymonitor.ds.DsWidgets.isStale(c, st, p);
                String sym = st.currencySymbol();
                if (stale) {
                    // 过期数据绝不冒充实时值
                    rv.setTextViewText(R.id.wDeepSeek, "DeepSeek " + sym + fmt2(st.current)
                            + " · " + MainActivity.fmtAgo(System.currentTimeMillis() - st.lastTs));
                    rv.setTextColor(R.id.wDeepSeek, C_WARN);
                } else {
                    rv.setTextViewText(R.id.wDeepSeek, "DeepSeek " + sym + fmt2(st.current)
                            + " · 今日 −" + sym + fmt2(st.todaySpend));
                    rv.setTextColor(R.id.wDeepSeek, C_DS);
                }
            }
        } catch (Throwable t) {
            // 记日志：这一行要是没渲染出来，必须能从 logcat 看出是"取数失败"还是"控件找不到"
            android.util.Log.w("wbmon", "ds widget line failed: " + t, t);
            try {
                rv.setTextViewText(R.id.wDeepSeek, "");
            } catch (Throwable ignored) {
            }
        }
    }

    private static String fmt2(double v) {
        return String.format(java.util.Locale.CHINA, "%.2f", v);
    }

    /** 只重绘、不联网。DeepSeek 采样成功后用它把小组件底部那一行立刻刷新。 */
    public static void renderAllFromCache(Context c) {
        try {
            Store.migrate(c);
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, WbWidget.class));
            Snapshot s = readCache(c);
            for (int id : ids) render(c, m, id, s);
        } catch (Throwable t) {
            android.util.Log.w("wbmon", "widget cache render failed: " + t, t);
        }
    }

    /** 与小组件共享的健康度算法（避免依赖 Activity） */
    static int healthOf(Snapshot s) {
        if (s.activeCount <= 0) return -1;
        int water = -1;
        boolean expired = false;
        for (Snapshot.Account a : s.accounts) {
            if (a.disabled) continue;
            if (a.error != null && a.error.length() > 0) continue;
            int p = a.cycleRemainPct();
            if (p >= 0 && (water < 0 || p < water)) water = p;
            if (a.expired) expired = true;
        }
        if (water < 0) water = 70;
        double signRate = s.signedCount * 100.0 / s.activeCount;
        double cred = expired ? 45 : 100;
        int v = (int) Math.round(water * 0.4 + signRate * 0.3 + cred * 0.3);
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    static String accLine(Snapshot.Account a) {
        String mark = a.signedToday ? "\u2713" : "\u2717";
        String name = a.name.length() > 8 ? a.name.substring(0, 8) : a.name;
        String tail = a.cycleRemainPct() >= 0 ? (" · 剩 " + a.cycleRemainPct() + "%") : "";
        return mark + " " + name + "  " + MainActivity.fmtNum(a.balance) + tail;
    }
}
