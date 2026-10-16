package io.github.workbuddymonitor.ds;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.workbuddymonitor.R;
import io.github.workbuddymonitor.Store;

/**
 * 主界面第 4 个 Tab「DeepSeek」的控制器。
 *
 * 定位：宿主 MainActivity 只负责把这个类挂上去；DeepSeek 的全部渲染、
 * 刷新、隐私遮蔽都在这里完成，从而与 WorkBuddy 积分那条渲染链互不干扰。
 *
 * 纪律（继承自余额哨兵与宿主的历史事故）：
 *   1. 过期数据绝不冒充实时值 —— 采样太旧时金额显示 "—" 并写明"N 分钟前"；
 *   2. 样本跨度不足 6 小时不给"可用天数"，避免线性外推的假精度；
 *   3. 宿主的隐私模式（一键隐藏金额）必须同样遮蔽 DeepSeek 金额。
 */
public final class DsPage {

    private static final String MASK = "\u2022\u2022\u2022";

    private final Activity a;
    private View root;

    private View dotStatus;
    private TextView tvStatus, tvFresh, tvBalance, tvSplit, tvToday, tvRunway;
    private TextView tvWeekTotal, tvTrendRange, tvTrendLow, tvTrendHigh;
    private TextView tvRecentCount, tvRecentEmpty, tvFootNote;
    private DsBarChartView chart7;
    private DsSparkView spark;
    private LinearLayout boxAlerts, boxRecent;
    private ImageButton btnRefresh;

    private boolean fetching;
    private Stats last;

    // ---- 概览页里的 DeepSeek 摘要卡（同一份数据，第二处渲染） ----
    private View ovCard, ovDot;
    private TextView ovBalance, ovToday, ovStatus;
    private Runnable ovClick;

    public DsPage(Activity a) {
        this.a = a;
    }

    /**
     * 概览页的 DeepSeek 摘要卡：让用户在概览就能同时看到 WorkBuddy 与 DeepSeek 两边的情况。
     * 点它跳到 DeepSeek 页。
     */
    public void bindOverview(View card, Runnable onClick) {
        if (card == null) return;
        ovCard = card;
        ovDot = card.findViewById(R.id.dsOvDot);
        ovBalance = card.findViewById(R.id.dsOvBalance);
        ovToday = card.findViewById(R.id.dsOvToday);
        ovStatus = card.findViewById(R.id.dsOvStatus);
        ovClick = onClick;
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ovClick != null) ovClick.run();
            }
        });
    }

    /** 由宿主 MainActivity 在 setContentView 之后调用。 */
    public void bind(View pageRoot) {
        this.root = pageRoot;
        dotStatus = pageRoot.findViewById(R.id.dsDotStatus);
        tvStatus = pageRoot.findViewById(R.id.dsTvStatus);
        tvFresh = pageRoot.findViewById(R.id.dsTvFresh);
        tvBalance = pageRoot.findViewById(R.id.dsTvBalance);
        tvSplit = pageRoot.findViewById(R.id.dsTvSplit);
        tvToday = pageRoot.findViewById(R.id.dsTvToday);
        tvRunway = pageRoot.findViewById(R.id.dsTvRunway);
        tvWeekTotal = pageRoot.findViewById(R.id.dsTvWeekTotal);
        tvTrendRange = pageRoot.findViewById(R.id.dsTvTrendRange);
        tvTrendLow = pageRoot.findViewById(R.id.dsTvTrendLow);
        tvTrendHigh = pageRoot.findViewById(R.id.dsTvTrendHigh);
        tvRecentCount = pageRoot.findViewById(R.id.dsTvRecentCount);
        tvRecentEmpty = pageRoot.findViewById(R.id.dsTvRecentEmpty);
        tvFootNote = pageRoot.findViewById(R.id.dsTvFootNote);
        chart7 = pageRoot.findViewById(R.id.dsChart7);
        spark = pageRoot.findViewById(R.id.dsSpark);
        boxAlerts = pageRoot.findViewById(R.id.dsBoxAlerts);
        boxRecent = pageRoot.findViewById(R.id.dsBoxRecent);
        btnRefresh = pageRoot.findViewById(R.id.dsBtnRefresh);

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                v.animate().rotationBy(360f).setDuration(650).start();
                refresh();
            }
        });
    }

    /** 只读本地采样重绘（不联网）。宿主每次刷新时顺带调一次即可。 */
    public void render() {
        render(DsRefreshTask.cached(a));
    }

    /** 立刻拉一次 DeepSeek 余额（联网）。 */
    public void refresh() {
        if (fetching) return;
        fetching = true;
        if (tvStatus != null) tvStatus.setText("正在获取…");
        DsRefreshTask.run(a.getApplicationContext(), new DsRefreshTask.Callback() {
            public void done(final boolean ok, final String error, final Stats st) {
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        fetching = false;
                        render(st);
                        io.github.workbuddymonitor.WbWidget.renderAllFromCache(a);
                    }
                });
            }
        });
    }

    /** 进入该 Tab 时调用：先落一帧本地数据，再按需补一次联网刷新。 */
    public void onShown() {
        render();
        try { DsScheduler.schedule(a); } catch (Throwable ignored) {}
        Stats st = last;
        if (st == null || !st.hasData) {
            if (new DsPrefs(a).hasKey()) refresh();
            return;
        }
        boolean stale = DsWidgets.isStale(a, st, new DsPrefs(a));
        if (stale) refresh();
    }

    // ==================== 渲染 ====================

    private void render(Stats st) {
        if (st == null || root == null) return;
        last = st;
        boolean privacy = Store.privacy(a);
        DsPrefs p = new DsPrefs(a);
        boolean stale = DsWidgets.isStale(a, st, p);

        // ---- 状态行 ----
        dotStatus.setBackgroundResource(
                !st.hasData ? R.drawable.dot_off : (stale ? R.drawable.ds_dot_warn : R.drawable.dot_live));
        if (!st.hasKey) tvStatus.setText("未配置 API Key");
        else if (!st.hasData) tvStatus.setText("等待首次采样");
        else if (stale) tvStatus.setText("数据已过期");
        else tvStatus.setText("已连接 DeepSeek");
        tvFresh.setText(st.hasData ? "· " + DsWidgets.ageText(st.lastTs) : "");
        tvFresh.setTextColor(stale ? 0xFFFFC46B : 0x8A9BB8D0);

        // ---- 余额大卡 ----
        if (privacy) {
            tvBalance.setText(MASK);
            tvSplit.setText("");
            tvToday.setText(MASK);
            tvRunway.setText("");
        } else if (!st.hasKey) {
            tvBalance.setText("—");
            tvSplit.setText("在「设置 → DeepSeek 余额」里填入 API Key");
            tvToday.setText("—");
            tvRunway.setText("");
        } else if (!st.hasData) {
            tvBalance.setText("—");
            tvSplit.setText(st.lastError.isEmpty() ? "等待首次采样" : st.lastError);
            tvToday.setText("—");
            tvRunway.setText("");
        } else {
            String sym = st.currencySymbol();
            tvBalance.setText(sym + fmt(st.current));
            tvBalance.setTextColor(stale ? 0x8A9BB8D0 : 0xFF8DA2FF);
            tvSplit.setText("赠金 " + sym + fmt(st.granted) + "  ·  充值 " + sym + fmt(st.toppedUp));
            tvToday.setText("−" + sym + fmt(st.todaySpend));
            tvToday.setTextColor(st.todaySpend > 0 ? 0xFFFFC46B : 0x8A9BB8D0);
            tvRunway.setText(runwayText(st));
        }

        // ---- 近 7 天柱状图 ----
        double[] d7 = st.hasData ? st.last7 : new double[7];
        chart7.setData(d7, dayLabels());
        chart7.setUnit(st.currencySymbol());
        double week = 0;
        for (double v : d7) week += v;
        tvWeekTotal.setText(privacy ? MASK : (week > 0 ? "合计 −" + st.currencySymbol() + fmt(week) : "无消耗"));

        // ---- 余额走势 ----
        renderTrend(privacy);

        // ---- 预警 ----
        renderAlerts(st, privacy, stale);

        // ---- 最近消耗 ----
        renderRecent(st, privacy);

        // ---- 概览页摘要卡 ----
        renderOverview(st, privacy, stale);

        // ---- 口径说明 ----
        String src = !st.hasData ? "尚未采到数据"
                : ("样本 " + sampleCount() + " 条 · 覆盖 " + spanText(st));
        tvFootNote.setText("数据来源：DeepSeek 官方 /user/balance（只有余额，没有用量接口）\n"
                + "因此「消耗」由定时采样余额、下降记消耗、上升记充值推算 —— 两次采样之间的多笔调用会合并成一条。\n"
                + src);
    }

    private void renderTrend(boolean privacy) {
        List<Sample> list = DsRefreshTask.history(a);
        // 取最近 48 个采样点画走势（约 12 小时 @15 分钟）
        int from = Math.max(0, list.size() - 48);
        int n = list.size() - from;
        if (privacy || n < 2) {
            spark.setData(new float[0]);
            tvTrendRange.setText("");
            tvTrendLow.setText("");
            tvTrendHigh.setText("");
            if (n < 2) tvTrendRange.setText("样本不足");
            return;
        }
        float[] vals = new float[n];
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            vals[i] = (float) list.get(from + i).total;
            lo = Math.min(lo, vals[i]);
            hi = Math.max(hi, vals[i]);
        }
        spark.setData(vals);
        String sym = list.get(list.size() - 1).currency;
        sym = "USD".equalsIgnoreCase(sym) ? "$" : "¥";
        tvTrendRange.setText(n + " 个采样点");
        tvTrendLow.setText("低 " + sym + fmt(lo));
        tvTrendHigh.setText("高 " + sym + fmt(hi));
    }

    private void renderAlerts(Stats st, boolean privacy, boolean stale) {
        boxAlerts.removeAllViews();
        DsPrefs p = new DsPrefs(a);
        int added = 0;

        if (!st.hasKey) {
            addAlert(0xFFFFC46B, "还没填 DeepSeek API Key",
                    "到「设置 → DeepSeek 余额」贴入 sk- 开头的 Key，即可开始采样。Key 只存在本机。");
            added++;
        } else if (!st.hasData) {
            addAlert(0xFFFFC46B, "还没采到余额",
                    st.lastError.isEmpty() ? "点右上角刷新键立刻取一次。" : st.lastError);
            added++;
        } else {
            if (stale) {
                addAlert(0xFFFFC46B, "数据已过期",
                        "最后更新 " + DsWidgets.ageText(st.lastTs) + "，点右上角刷新键补一次。");
                added++;
            }
            double th = p.getWarnThreshold();
            if (p.isWarnEnabled() && st.current < th) {
                addAlert(0xFFFF8A9B, "余额低于预警线",
                        "当前 " + Stats.money(st.currency, st.current)
                                + "，已低于 " + Stats.money(st.currency, th) + "。");
                added++;
            }
            if (st.hasData && !st.rateReliable && added < 3) {
                addAlert(0x8A9BB8D0, "样本积累中",
                        "采样跨度还不足 6 小时，暂不预估可用天数 —— 硬算出来的天数不准。");
                added++;
            }
        }

        if (added == 0) {
            boxAlerts.setVisibility(View.GONE);
        } else {
            boxAlerts.setVisibility(View.VISIBLE);
        }
    }

    private void addAlert(int color, String title, String desc) {
        View v = LayoutInflater.from(a).inflate(R.layout.ds_item_alert, boxAlerts, false);
        View bar = v.findViewById(R.id.ds_alBar);
        TextView t = v.findViewById(R.id.ds_alTitle);
        TextView d = v.findViewById(R.id.ds_alDesc);
        bar.setBackgroundColor(color);
        t.setText(title);
        t.setTextColor(color);
        d.setText(desc);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = boxAlerts.getChildCount() == 0 ? 14 : 8;
        boxAlerts.addView(v, lp);
    }

    private void renderRecent(Stats st, boolean privacy) {
        boxRecent.removeAllViews();
        List<Stats.Spend> rec = st.hasData ? st.recent : new java.util.ArrayList<Stats.Spend>();
        int n = Math.min(rec.size(), 20);
        tvRecentCount.setText(n > 0 ? ("近 " + n + " 条") : "");
        if (n == 0) {
            // 说清为什么没有 —— 否则看起来像功能坏了。
            // 「消耗」必须由两次采样之间的余额下降推算（DeepSeek 官方没有用量接口），
            // 所以刚装好、或余额一直没动的时候，这里本来就该是空的。
            tvRecentEmpty.setText("还没有消耗记录。\n"
                    + "「消耗」是靠两次采样之间的余额下降推算出来的 —— DeepSeek 官方只有余额接口，没有用量接口。\n"
                    + (st.hasData
                        ? ("本机现有 " + sampleCount() + " 条采样、总跨度约 " + spanText(st)
                           + "。余额一旦下降，这里会立刻出现一条记录。")
                        : "等第一次采样成功后开始累计。"));
            tvRecentEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvRecentEmpty.setVisibility(View.GONE);
        String sym = st.currencySymbol();
        for (int i = 0; i < n; i++) {
            Stats.Spend sp = rec.get(i);
            View v = LayoutInflater.from(a).inflate(R.layout.ds_item_spend, boxRecent, false);
            TextView t = v.findViewById(R.id.ds_spTitle);
            TextView sub = v.findViewById(R.id.ds_spSub);
            TextView amt = v.findViewById(R.id.ds_spAmount);
            t.setText("消耗 " + sym + fmt(sp.amount));
            sub.setText(sp.time(sp.ts) + "  ·  余额 " + sym + fmt(sp.from) + " → " + sym + fmt(sp.to));
            amt.setText(privacy ? MASK : ("−" + sym + fmt(sp.amount)));
            boxRecent.addView(v);
        }
    }

    // ==================== 工具 ====================

    private String runwayText(Stats st) {
        if (!st.hasData) return "";
        String sym = st.currencySymbol();
        if (st.daysLeft > 0) {
            return "按近 7 天速度约可用 " + st.daysLeft + " 天 · 日均 "
                    + sym + fmt(st.burnPerDay);
        }
        if (st.rateReliable) return "近 7 天无消耗 · 日均 " + sym + fmt(st.burnPerDay);
        return "样本不足 6 小时，暂不预估天数";
    }

    private int sampleCount() {
        return DsRefreshTask.history(a).size();
    }

    private String spanText(Stats st) {
        long ms = st.historySpanMs;
        if (ms <= 0) return "—";
        long h = ms / 3_600_000L;
        if (h < 1) return (ms / 60_000L) + " 分钟";
        if (h < 48) return h + " 小时";
        return (h / 24) + " 天";
    }

    private String[] dayLabels() {
        String[] out = new String[7];
        SimpleDateFormat f = new SimpleDateFormat("M/d", Locale.CHINA);
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -6);
        for (int i = 0; i < 7; i++) {
            out[i] = f.format(new Date(c.getTimeInMillis()));
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    /**
     * 概览页摘要卡：一眼看到 DeepSeek 余额与今日消耗，点进去看详情。
     * 读数口径与 DeepSeek 页完全一致，不会出现两处数字不一样。
     */
    private void renderOverview(Stats st, boolean privacy, boolean stale) {
        if (ovCard == null) return;
        ovDot.setBackgroundResource(
                !st.hasData ? R.drawable.dot_off : (stale ? R.drawable.ds_dot_warn : R.drawable.dot_live));

        if (!st.hasKey) {
            ovBalance.setText("—");
            ovToday.setText("");
            ovStatus.setText("未配置 API Key");
        } else if (!st.hasData) {
            ovBalance.setText(privacy ? MASK : "—");
            ovToday.setText("");
            ovStatus.setText("等待首次采样");
        } else {
            ovBalance.setText(privacy ? MASK : (st.currencySymbol() + fmt(st.current)));
            ovBalance.setTextColor(stale ? 0x8A9BB8D0 : 0xFF8DA2FF);
            ovToday.setText(privacy ? MASK : ("今日 −" + st.currencySymbol() + fmt(st.todaySpend)));
            ovStatus.setText(DsWidgets.ageText(st.lastTs)
                    + (stale ? " · 已过期" : (st.daysLeft > 0 ? " · 约可用 " + st.daysLeft + " 天" : "")));
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.CHINA, "%.2f", v);
    }
}
