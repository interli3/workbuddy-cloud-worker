package io.github.workbuddymonitor.ds;

import android.content.Context;

import java.util.List;

/** 一次"拉取余额 → 落库 → 通知 → 刷新小组件"的完整动作。小组件与定时器共用它。 */
public final class DsRefreshTask {

    public interface Callback {
        void done(boolean ok, String error, Stats stats);
    }

    private DsRefreshTask() {}

    public static void run(final Context app, final Callback cb) {
        final DsPrefs p = new DsPrefs(app);
        final String key = p.getKey();
        if (key.isEmpty()) {
            p.setLastError("尚未配置 API Key");
            Stats st = Stats.compute(app, p);
            if (cb != null) cb.done(false, "尚未配置 API Key", st);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                BalanceApi.Result r = BalanceApi.fetch(key);
                boolean ok = r.ok && r.sample != null;
                Stats st;
                if (ok) {
                    p.setLastError("");
                    p.setLastOkTs(System.currentTimeMillis());
                    p.setCurrency(r.sample.currency == null ? "CNY" : r.sample.currency);
                    p.cacheSnapshot(r.sample.total, r.sample.granted, r.sample.toppedUp);
                    DsStore.append(app, r.sample);
                } else {
                    p.setLastError(r.error == null ? "未知错误" : r.error);
                }
                st = Stats.compute(app, p);
                if (ok) DsNotifier.maybeNotify(app, p, st);
                DsScheduler.schedule(app, p.getIntervalMin());
                // 只重绘、不联网：把 WorkBuddy 小组件底部那行 DeepSeek 余额立刻刷新
                try {
                    io.github.workbuddymonitor.WbWidget.renderAllFromCache(app);
                } catch (Throwable ignored) {
                }
                if (cb != null) cb.done(ok, r.error, st);
            }
        }, "ds-refresh").start();
    }

    public static Stats cached(Context c) {
        return Stats.compute(c, new DsPrefs(c));
    }

    public static List<Sample> history(Context c) {
        return DsStore.load(c);
    }
}
