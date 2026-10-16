package io.github.workbuddymonitor;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Liquid Glass 多账号控制台：核心用量固定展示，概览/账户/记录分区切换。
 * 支持隐私模式（一键隐藏所有金额）。
 */
public class MainActivity extends Activity {

    // 深色玻璃主题配色（2026-09-28 改版）：深底 + 反白三级文字 + 霓虹青蓝
    // 注意：C_ACCENT_DEEP 在深色主题下取"更亮"的一档，保证深底小字可读
    private static final int C_TEXT = 0xFFEAF4FF;
    private static final int C_DIM = 0xB3D8E8F8;
    private static final int C_FAINT = 0x8A9BB8D0;
    private static final int C_ACCENT = 0xFF4FA3FF;
    private static final int C_ACCENT_DEEP = 0xFF8FD4FF;
    private static final int C_BLUE = 0xFF5FA8FF;
    private static final int C_WARN = 0xFFFFC46B;
    private static final int C_DANGER = 0xFFFF8A9B;

    private static final String MASK = "\u2022\u2022\u2022";

    /** 供外部（DeepSeek 小组件 / 通知）指定进来后打开哪个 Tab。3 = DeepSeek。 */
    public static final String EXTRA_OPEN_TAB = "io.github.workbuddymonitor.OPEN_TAB";
    public static final int TAB_DEEPSEEK = 3;

    private View dotStatus;
    private TextView tvGreet, tvStatus, tvFresh, tvCloudTag;
    private TextView tvBalance, tvBalanceHint, tvUsedToday, tvRunway;
    private TextView tvScore, tvScoreWord, tvScoreWhy;
    private MiniChart chart7d;
    private TextView tvActiveCount, tvAccCount, tvFlowCount, tvFlowAllCount, tvRecentCount, tvInsightCount, tvFootNote;
    private TextView tabOverview, tabAccounts, tabHistory, tabDeepSeek;
    private LinearLayout boxInsight, boxInsightHead, boxInsightPanel, boxHealthAccounts, boxActive, boxAccounts, boxFlow, boxFlowAll, boxRecent;
    private LinearLayout pageOverview, pageAccounts, pageHistory;
    /** DeepSeek 余额页的根容器（第 4 个 Tab） */
    private LinearLayout pageDeepSeek;
    /** 切到 DeepSeek 页时要收起的 WorkBuddy 区块（状态行 / 余额大卡 / 页脚） */
    private View headWb, heroWb;
    /** DeepSeek 余额页控制器：另一套数据、另一条刷新链，与 WorkBuddy 积分互不干扰 */
    private io.github.workbuddymonitor.ds.DsPage dsPage;
    private ImageButton btnRefresh, btnSettings, btnPrivacy;
    private ScrollView mainScroll;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean fetching;
    private long dataTs;
    private double lastBalance = -1;
    private ValueAnimator balAnim;
    private final List<ObjectAnimator> pulses = new ArrayList<ObjectAnimator>();
    private boolean privacy;
    private Snapshot lastSnap;
    /** 上一次拉取失败的各通道原因（局域网/公网/会合点），用于界面直接显示排障信息 */
    private String lastErr = "";
    /** 当前展示的数据是否已过期（过期时所有金额类字段一律不冒充实时值） */
    private boolean staleData;

    private final Runnable tick = new Runnable() {
        public void run() {
            load();
            ui.postDelayed(this, Math.max(Store.interval(MainActivity.this), 15_000L));
        }
    };

    private final Runnable fresh = new Runnable() {
        public void run() {
            updateFresh();
            ui.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.migrate(this);
        privacy = Store.privacy(this);
        setContentView(R.layout.activity_main);

        tvGreet = findViewById(R.id.tvGreet);
        dotStatus = findViewById(R.id.dotStatus);
        tvStatus = findViewById(R.id.tvStatus);
        tvFresh = findViewById(R.id.tvFresh);
        tvCloudTag = findViewById(R.id.tvCloudTag);
        tvBalance = findViewById(R.id.tvBalance);
        tvBalanceHint = findViewById(R.id.tvBalanceHint);
        tvUsedToday = findViewById(R.id.tvUsedToday);
        tvRunway = findViewById(R.id.tvRunway);
        tvScore = findViewById(R.id.tvScore);
        tvScoreWord = findViewById(R.id.tvScoreWord);
        tvScoreWhy = findViewById(R.id.tvScoreWhy);
        chart7d = findViewById(R.id.chart7d);
        boxInsight = findViewById(R.id.boxInsight);
        boxInsightHead = findViewById(R.id.boxInsightHead);
        boxInsightPanel = findViewById(R.id.boxInsightPanel);
        boxHealthAccounts = findViewById(R.id.boxHealthAccounts);
        boxActive = findViewById(R.id.boxActive);
        boxAccounts = findViewById(R.id.boxAccounts);
        boxFlow = findViewById(R.id.boxFlow);
        boxFlowAll = findViewById(R.id.boxFlowAll);
        boxRecent = findViewById(R.id.boxRecent);
        tvInsightCount = findViewById(R.id.tvInsightCount);
        tvActiveCount = findViewById(R.id.tvActiveCount);
        tvAccCount = findViewById(R.id.tvAccCount);
        tvFlowCount = findViewById(R.id.tvFlowCount);
        tvFlowAllCount = findViewById(R.id.tvFlowAllCount);
        tvRecentCount = findViewById(R.id.tvRecentCount);
        tvFootNote = findViewById(R.id.tvFootNote);
        tabOverview = findViewById(R.id.tabOverview);
        tabAccounts = findViewById(R.id.tabAccounts);
        tabHistory = findViewById(R.id.tabHistory);
        tabDeepSeek = findViewById(R.id.tabDeepSeek);
        pageOverview = findViewById(R.id.pageOverview);
        pageAccounts = findViewById(R.id.pageAccounts);
        pageHistory = findViewById(R.id.pageHistory);
        pageDeepSeek = findViewById(R.id.dsPageRoot);
        headWb = findViewById(R.id.headWb);
        heroWb = findViewById(R.id.heroWb);
        mainScroll = findViewById(R.id.mainScroll);

        dsPage = new io.github.workbuddymonitor.ds.DsPage(this);
        dsPage.bind(pageDeepSeek);
        // 概览页里也放一张 DeepSeek 摘要卡，点它跳到 DeepSeek 页
        dsPage.bindOverview(findViewById(R.id.dsOvCard), new Runnable() {
            public void run() { showPage(TAB_DEEPSEEK); }
        });

        btnRefresh = findViewById(R.id.btnRefresh);
        btnSettings = findViewById(R.id.btnSettings);
        btnPrivacy = findViewById(R.id.btnPrivacy);

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                v.animate().rotationBy(360f).setDuration(650)
                        .setInterpolator(new LinearInterpolator()).start();
                load();
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        btnPrivacy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                privacy = !privacy;
                Store.setPrivacy(MainActivity.this, privacy);
                Snapshot s = lastSnap;
                if (s != null) render(s);
            }
        });
        tabOverview.setOnClickListener(v -> showPage(0));
        tabAccounts.setOnClickListener(v -> showPage(1));
        tabHistory.setOnClickListener(v -> showPage(2));
        tabDeepSeek.setOnClickListener(v -> showPage(3));
        tvFlowCount.setOnClickListener(v -> showPage(2));

        tvGreet.setText("用量中心");
        renderCache();
        load();

        // 从 DeepSeek 小组件 / 通知进来时直接落到第 4 个 Tab
        if (getIntent() != null && getIntent().getIntExtra(EXTRA_OPEN_TAB, -1) == TAB_DEEPSEEK) {
            showPage(TAB_DEEPSEEK);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getIntExtra(EXTRA_OPEN_TAB, -1) == TAB_DEEPSEEK) {
            showPage(TAB_DEEPSEEK);
        }
    }

    private void showPage(int page) {
        pageOverview.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        pageAccounts.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        pageHistory.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        pageDeepSeek.setVisibility(page == 3 ? View.VISIBLE : View.GONE);

        // DeepSeek 是另一本账：切到它时把 WorkBuddy 的状态行 / 余额大卡 / 页脚一并收起，
        // 避免两套金额同屏出现，让人分不清哪个是哪个。
        boolean wb = page != TAB_DEEPSEEK;
        headWb.setVisibility(wb ? View.VISIBLE : View.GONE);
        heroWb.setVisibility(wb ? View.VISIBLE : View.GONE);
        tvFootNote.setVisibility(wb ? View.VISIBLE : View.GONE);

        TextView[] tabs = {tabOverview, tabAccounts, tabHistory, tabDeepSeek};
        for (int i = 0; i < tabs.length; i++) {
            boolean active = i == page;
            tabs[i].setBackgroundResource(active ? R.drawable.bg_segment_active : R.drawable.bg_segment_idle);
            tabs[i].setTextColor(active ? C_ACCENT_DEEP : C_DIM);
        }
        if (page == TAB_DEEPSEEK && dsPage != null) dsPage.onShown();
        mainScroll.post(() -> mainScroll.smoothScrollTo(0, 0));
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvGreet.setText("用量中心");
        ui.removeCallbacks(tick);
        ui.removeCallbacks(fresh);
        ui.postDelayed(tick, Math.max(Store.interval(this), 15_000L));
        ui.post(fresh);
        // 回到前台时同步刷新 DeepSeek 页（只读缓存；联网刷新由它自己的节拍控制）
        if (dsPage != null) dsPage.render();
        // 每次打开 App 都重新武装后台刷新链。
        // 历史上刷新链只在"拉取成功""开机""覆盖安装"时才续期，一旦被系统打断，
        // 小组件会永久停在旧快照上；这里让"打开 App"成为无条件自愈动作。
        try {
            Scheduler.schedule(this);
        } catch (Throwable ignore) {
        }
        // DeepSeek 那条刷新链同理必须自愈：am force-stop / 系统清理会把闹钟一起清掉，
        // 只在设置里排一次程的话，采样会永久停摆（与宿主同一类历史事故）。
        try {
            io.github.workbuddymonitor.ds.DsScheduler.schedule(this);
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(fresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPulses();
        if (balAnim != null) balAnim.cancel();
    }

    private static String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 5) return "夜深了";
        if (h < 11) return "早上好";
        if (h < 14) return "中午好";
        if (h < 18) return "下午好";
        return "晚上好";
    }

    private void renderCache() {
        String c = Store.cache(this);
        if (c != null && c.length() > 0) {
            Snapshot s = Snapshot.fromCache(c);
            if (s != null) {
                s.online = false;
                lastSnap = s;
                render(s);
            }
        }
    }

    private void load() {
        if (fetching) return;
        fetching = true;
        tvStatus.setText(R.string.lbl_loading);
        Fetcher.fetch(this, new Fetcher.Cb() {
            public void onResult(final Snapshot s, final String err) {
                ui.post(new Runnable() {
                    public void run() {
                        fetching = false;
                        lastErr = err == null ? "" : err;
                        if (s != null) {
                            lastSnap = s;
                            render(s);
                        } else {
                            tvStatus.setText(R.string.lbl_offline);
                            dotStatus.setBackgroundResource(R.drawable.dot_off);
                        }
                        Scheduler.schedule(MainActivity.this);
                        WbWidget.pushAll(MainActivity.this);
                        // DeepSeek 页顺带落一帧本地数据（只读缓存，不联网；联网由它自己的刷新键/闹钟负责）
                        if (dsPage != null) dsPage.render();
                    }
                });
            }
        });
    }

    // ==================== 渲染 ====================

    private void render(Snapshot s) {
        if (s == null) {
            dotStatus.setBackgroundResource(R.drawable.dot_off);
            tvStatus.setText(R.string.lbl_no_data);
            return;
        }
        dataTs = s.ts > 0 ? s.ts : System.currentTimeMillis();

        // 过期判定：离线（走缓存）或时间戳太旧，都不允许把旧数字当实时值展示。
        final boolean stale = !s.online || s.isStale(Store.staleMs(this));
        staleData = stale;

        dotStatus.setBackgroundResource(stale ? R.drawable.dot_off : R.drawable.dot_live);
        tvStatus.setText(stale ? getString(R.string.lbl_offline) : getString(R.string.lbl_online));
        updateFresh();

        tvCloudTag.setText(stale ? "数据过期" : (s.cloud ? "实时" : "本地"));
        tvCloudTag.setTextColor(stale ? C_WARN : C_ACCENT);

        // 余额大卡
        animateBalance(s.totalBalance);
        StringBuilder hb = new StringBuilder();
        if (s.accountCount > 0) {
            hb.append(s.activeCount).append(" 个账号在用 · 已签 ").append(s.signedCount)
              .append("/").append(s.activeCount);
        }
        tvBalanceHint.setText(hb.toString());

        // 今日消耗小卡 + 可用天数
        if (privacy) {
            tvUsedToday.setText(MASK);
            tvUsedToday.setTextColor(C_FAINT);
        } else if (stale) {
            // 数据已过期：宁可显示"未知"，也不显示一个与真实不符的数字。
            // （历史事故：界面长期显示 35.2，真实应为 102.94，用户据此判断"数字是错的"。）
            tvUsedToday.setText("—");
            tvUsedToday.setTextColor(C_WARN);
        } else {
            tvUsedToday.setText(s.totalUsedToday > 0 ? fmtNum(s.totalUsedToday) : "0");
            tvUsedToday.setTextColor(s.totalUsedToday > 0 ? C_TEXT : C_FAINT);
        }
        double[] rw = runway(s);
        if (privacy) {
            tvRunway.setText("");
        } else if (rw != null) {
            tvRunway.setText("按最近速度约可用 " + (rw[1] >= 10 ? String.valueOf((int) rw[1])
                    : String.format(Locale.US, "%.1f", rw[1])) + " 天");
            tvRunway.setTextColor(rw[1] < 7 ? C_DANGER : C_FAINT);
        } else {
            tvRunway.setText("");
        }

        render7d(s);
        renderHealth(s);
        renderInsights(s);
        renderActive(s);
        renderAccounts(s);
        renderFlow(s);
        renderRecent(s);

        String errLine = lastErr.replace('\n', ' ');
        tvFootNote.setText((stale ? "数据未刷新（" + fmtAgo(s.ageMs()) + "）"
                : ("数据源 MQTT · 更新 " + fmtTime(s.ts)))
                + " · 累计消耗 " + money(s.totalUsed)
                + (stale && errLine.length() > 0 ? " · " + errLine : "")
                + " · v" + Store.appVer(this));
    }

    private void animateBalance(double v) {
        if (balAnim != null) { balAnim.cancel(); balAnim = null; }
        if (privacy) {
            tvBalance.setText(MASK);
            lastBalance = v;
            return;
        }
        final double from = lastBalance;
        if (from < 0 || Math.abs(from - v) < 0.01) {
            tvBalance.setText(fmtNum(v));
            lastBalance = v;
            return;
        }
        final double to = v;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(600);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator an) {
                float f = ((Float) an.getAnimatedValue());
                tvBalance.setText(fmtNum(from + (to - from) * f));
            }
        });
        balAnim = a;
        a.start();
        lastBalance = v;
    }

    /** 余额可用天数（今日消耗优先、否则按本月日均外推）；null = 无从估计 */
    private double[] runway(Snapshot s) {
        if (s.totalBalance <= 0) return null;
        double daily = s.totalUsedToday;
        if (daily <= 0 && s.usedMonth > 0) {
            int dom = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
            daily = s.usedMonth / Math.max(1, dom);
        }
        if (daily <= 0) return null;
        return new double[]{daily, s.totalBalance / daily};
    }

    // ==================== 7 日趋势 ====================

    private void render7d(Snapshot s) {
        if (privacy) { chart7d.setData(new float[0]); return; }
        Calendar c0 = Calendar.getInstance();
        c0.set(Calendar.HOUR_OF_DAY, 0);
        c0.set(Calendar.MINUTE, 0);
        c0.set(Calendar.SECOND, 0);
        c0.set(Calendar.MILLISECOND, 0);
        long today0 = c0.getTimeInMillis();
        float[] days = new float[7];
        for (Snapshot.Flow f : s.flow) {
            if (f.delta >= 0 || f.ts <= 0) continue;
            long d = (today0 - f.ts) / 86400000L;
            if (d < 0) d = 0;
            if (d > 6) continue;
            days[6 - (int) d] += (float) (-f.delta);
        }
        chart7d.setData(days);
    }

    // ==================== 健康度 ====================

    private void renderHealth(Snapshot s) {
        int score = healthScore(s);
        if (score < 0) {
            tvScore.setText("--");
            tvScore.setTextColor(C_FAINT);
            tvScoreWord.setText("暂无数据");
            tvScoreWhy.setText("");
            boxHealthAccounts.removeAllViews();
            return;
        }
        tvScore.setText(String.valueOf(score));
        int c = score >= 75 ? C_ACCENT : (score >= 50 ? C_WARN : C_DANGER);
        tvScore.setTextColor(c);
        tvScoreWord.setText(score >= 75 ? "良好" : (score >= 50 ? "一般" : "需要处理"));
        tvScoreWord.setTextColor(c);
        tvScoreWhy.setText(healthWhy(s));
        renderHealthAccounts(s);
    }

    private String healthWhy(Snapshot s) {
        int attention = 0;
        for (Snapshot.Account a : s.accounts) {
            if (a.disabled || a.expired || (a.error != null && a.error.length() > 0)) {
                attention++;
                continue;
            }
            int p = a.cycleRemainPct();
            if (p >= 0 && p <= 30) attention++;
        }
        int healthy = Math.max(0, s.activeCount - attention);
        return healthy + " 个健康 · " + attention + " 个高风险 · 按最弱账号评估";
    }

    private void renderHealthAccounts(Snapshot s) {
        boxHealthAccounts.removeAllViews();
        for (int i = 0; i < s.accounts.size(); i++) {
            if (i > 0) gap(boxHealthAccounts, 8);
            boxHealthAccounts.addView(buildHealthAccount(s.accounts.get(i)));
        }
    }

    private View buildHealthAccount(Snapshot.Account a) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setBackgroundResource(R.drawable.bg_liquid_row);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextColor(C_TEXT);
        name.setTextSize(14);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        int pct = a.cycleRemainPct();
        boolean bad = a.disabled || a.expired || (a.error != null && a.error.length() > 0)
                || (pct >= 0 && pct <= 15);
        boolean warn = !bad && pct >= 0 && pct <= 30;
        int stateColor = bad ? C_DANGER : (warn ? C_WARN : 0xFF5FE3B4);

        TextView state = new TextView(this);
        state.setText(bad ? "需处理" : (warn ? "留意" : "健康"));
        state.setTextColor(stateColor);
        state.setTextSize(12);
        state.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        top.addView(state);
        card.addView(top);

        TextView detail = new TextView(this);
        String quota = pct >= 0 ? ("周期剩余 " + pct + "%") : "额度状态未知";
        String cred = a.expired ? "凭证已过期" : (a.expireDays >= 0 ? ("凭证 " + a.expireDays + " 天") : "凭证正常");
        detail.setText(quota + "  ·  " + cred + "  ·  " + (a.signedToday ? "今日已签" : "今日未签"));
        detail.setTextColor(C_DIM);
        detail.setTextSize(11.5f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(5);
        card.addView(detail, detailLp);

        if (pct >= 0) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackgroundResource(R.drawable.bg_progress_track);
            View fill = new View(this);
            fill.setBackgroundResource(pct >= 40 ? R.drawable.bg_progress_mint
                    : (pct >= 15 ? R.drawable.bg_progress_warn : R.drawable.bg_progress_danger));
            bar.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, pct)));
            View rest = new View(this);
            bar.addView(rest, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, 100 - pct)));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(5));
            barLp.topMargin = dp(9);
            card.addView(bar, barLp);
        }
        card.setOnClickListener(v -> showPage(1));
        return card;
    }

    private int healthScore(Snapshot s) {
        if (s.activeCount <= 0) return -1;
        int water = -1;
        boolean anyExpired = false;
        for (Snapshot.Account a : s.accounts) {
            if (a.disabled) continue;
            if (a.error != null && a.error.length() > 0) continue;
            int p = a.cycleRemainPct();
            if (p >= 0 && (water < 0 || p < water)) water = p;
            if (a.expired) anyExpired = true;
        }
        if (water < 0) water = 70;
        double signRate = s.signedCount * 100.0 / s.activeCount;
        double cred = anyExpired ? 45 : 100;
        int v = (int) Math.round(water * 0.4 + signRate * 0.3 + cred * 0.3);
        return Math.max(0, Math.min(100, v));
    }

    // ==================== 智能提醒 ====================

    private void renderInsights(Snapshot s) {
        boxInsight.removeAllViews();
        int added = 0;

        for (Snapshot.Account a : s.accounts) {
            if (a.disabled) continue;
            if (a.expired) {
                addInsight(C_DANGER, R.drawable.bg_progress_danger, getString(R.string.ins_cred_expired),
                        String.format(getString(R.string.ins_cred_expired_d), a.name), 1);
                added++;
            }
            if (added >= 2) break;
        }

        for (Snapshot.Account a : s.accounts) {
            if (a.disabled || a.cycleSize <= 0 || added >= 4) continue;
            int p = a.cycleRemainPct();
            if (p >= 0 && p <= 20) {
                addInsight(p <= 10 ? C_DANGER : C_WARN,
                        p <= 10 ? R.drawable.bg_progress_danger : R.drawable.bg_progress_warn,
                        getString(R.string.ins_cycle_low),
                        String.format(getString(R.string.ins_cycle_low_d), a.name, p,
                                fmtNum(a.cycleSize - a.cycleUsed), shortDate(a.cycleEnd)), 1);
                added++;
            }
        }

        if (added < 4 && s.activeCount > s.signedCount) {
            StringBuilder names = new StringBuilder();
            int n = 0;
            for (Snapshot.Account a : s.accounts) {
                if (a.disabled || a.signedToday) continue;
                if (names.length() > 0) names.append("、");
                names.append(a.name);
                if (++n >= 3) break;
            }
            addInsight(C_WARN, R.drawable.bg_progress_warn, getString(R.string.ins_unsigned),
                    String.format(getString(R.string.ins_unsigned_d), s.activeCount - s.signedCount, names.toString()), 1);
            added++;
        }

        if (added < 4) {
            int failed = 0;
            for (Snapshot.Task t : s.tasks) if ("failed".equals(t.state)) failed++;
            if (failed > 0) {
                addInsight(C_WARN, R.drawable.bg_progress_warn, getString(R.string.ins_failed),
                        String.format(getString(R.string.ins_failed_d), failed), 2);
                added++;
            }
        }

        if (added < 4 && !s.online) {
            addInsight(C_WARN, R.drawable.bg_progress_warn, getString(R.string.ins_offline),
                    getString(R.string.ins_offline_d), -1);
            added++;
        }

        if (added == 0) {
            boxInsightHead.setVisibility(View.GONE);
            boxInsightPanel.setVisibility(View.GONE);
            return;
        }
        boxInsightHead.setVisibility(View.VISIBLE);
        boxInsightPanel.setVisibility(View.VISIBLE);
        tvInsightCount.setText(added + " 条");
    }

    private void addInsight(int color, int barRes, String title, String desc, int targetPage) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_insight, boxInsight, false);
        View bar = v.findViewById(R.id.vBar);
        TextView t = v.findViewById(R.id.tvTitle);
        TextView d = v.findViewById(R.id.tvDesc);
        TextView action = v.findViewById(R.id.tvAction);
        bar.setBackgroundResource(barRes);
        t.setText(title);
        t.setTextColor(color);
        d.setText(desc);
        if (targetPage >= 0) {
            action.setVisibility(View.VISIBLE);
            v.setOnClickListener(x -> showPage(targetPage));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 0;
        boxInsight.addView(v, lp);
    }

    // ==================== 当前执行任务 ====================

    private void renderActive(Snapshot s) {
        boxActive.removeAllViews();
        stopPulses();
        List<Snapshot.Task> act = s.activeTasks();

        tvActiveCount.setText(staleData ? "状态未知"
                : (act.size() > 0
                        ? String.format(getString(R.string.lbl_running_count), act.size())
                        : getString(R.string.st_idle)));

        if (act.size() == 0) {
            addEmpty(boxActive, staleData
                    ? ("数据未刷新（" + fmtAgo(System.currentTimeMillis() - (lastSnap == null
                            ? System.currentTimeMillis() : lastSnap.ts)) + "），暂无法确认电脑端是否有任务在跑")
                    : (getString(R.string.lbl_no_task) + "\n电脑端发起对话后会实时显示进度"));
            return;
        }
        for (int i = 0; i < act.size(); i++) {
            if (i > 0) gap(boxActive, 10);
            boxActive.addView(buildTask(act.get(i)));
        }
    }

    private View buildTask(Snapshot.Task t) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_task, boxActive, false);
        View pulse = v.findViewById(R.id.vPulse);
        TextView tvState = v.findViewById(R.id.tvState);
        TextView tvTitle = v.findViewById(R.id.tvTitle);
        TextView tvCost = v.findViewById(R.id.tvCost);
        TextView tvMeta = v.findViewById(R.id.tvMeta);
        View fill = v.findViewById(R.id.vFill);
        View rest = v.findViewById(R.id.vRest);

        boolean running = "running".equals(t.state);
        tvState.setText(running ? R.string.st_running : R.string.st_waiting);
        pulse.setBackgroundResource(running ? R.drawable.dot_pulse : R.drawable.dot_waiting);

        tvTitle.setText(t.title.length() > 0 ? t.title : "(无标题任务)");
        if (privacy && t.cost > 0) {
            tvCost.setText(MASK);
        } else {
            tvCost.setText(t.cost > 0 ? "-" + fmtNum(t.cost) : "");
        }

        int pct = t.progress <= 0 ? 5 : Math.min(100, t.progress);
        tvMeta.setText(pct + "% · " + t.meta);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, pct));
        rest.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 100 - pct));
        fill.setBackgroundResource(running ? R.drawable.bg_progress_mint : R.drawable.bg_progress_warn);

        if (running) {
            ObjectAnimator oa = ObjectAnimator.ofFloat(pulse, "alpha", 1f, 0.25f);
            oa.setDuration(1200);
            oa.setRepeatCount(ObjectAnimator.INFINITE);
            oa.setRepeatMode(ObjectAnimator.REVERSE);
            oa.start();
            pulses.add(oa);
        }
        return v;
    }

    private void stopPulses() {
        for (ObjectAnimator oa : pulses) {
            try { oa.cancel(); } catch (Throwable ignore) { }
        }
        pulses.clear();
    }

    // ==================== 账号 ====================

    private static final int[] AVATAR_BG = {
            R.drawable.bg_avatar, R.drawable.bg_avatar_blue, R.drawable.bg_avatar_purple,
            R.drawable.bg_avatar_amber, R.drawable.bg_avatar_rose};
    private static final int[] AVATAR_FG = {
            0xFF5FE3B4, 0xFF7FC8FF, 0xFFC4A6FF, 0xFFFFC46B, 0xFFFF9EB0};

    private void renderAccounts(Snapshot s) {
        boxAccounts.removeAllViews();
        tvAccCount.setText(s.accounts.size() + " 个"
                + (s.activeCount != s.accounts.size() ? (" · " + s.activeCount + " 在用") : ""));

        if (s.accounts.size() == 0) {
            addEmpty(boxAccounts, getString(R.string.lbl_no_account));
            return;
        }
        for (int i = 0; i < s.accounts.size(); i++) {
            if (i > 0) gap(boxAccounts, 10);
            boxAccounts.addView(buildAccount(s.accounts.get(i)));
        }
    }

    private View buildAccount(final Snapshot.Account a) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_account, boxAccounts, false);

        TextView tvAvatar = v.findViewById(R.id.tvAvatar);
        TextView tvName = v.findViewById(R.id.tvName);
        TextView tvRegion = v.findViewById(R.id.tvRegion);
        TextView tvSub = v.findViewById(R.id.tvSub);
        TextView tvBalance = v.findViewById(R.id.tvBalance);
        LinearLayout boxCycle = v.findViewById(R.id.boxCycle);
        TextView tvCycleLeft = v.findViewById(R.id.tvCycleLeft);
        TextView tvCyclePct = v.findViewById(R.id.tvCyclePct);
        TextView tvCycleEnd = v.findViewById(R.id.tvCycleEnd);
        View fill = v.findViewById(R.id.vFill);
        View rest = v.findViewById(R.id.vRest);
        TextView tvChipToday = v.findViewById(R.id.tvChipToday);
        TextView tvChipSign = v.findViewById(R.id.tvChipSign);
        TextView tvChipCred = v.findViewById(R.id.tvChipCred);
        final LinearLayout boxDetail = v.findViewById(R.id.boxDetail);
        TextView tvDetail = v.findViewById(R.id.tvDetail);

        int ci = Math.abs((a.id != null && a.id.length() > 0 ? a.id : a.name).hashCode()) % AVATAR_BG.length;
        tvAvatar.setBackgroundResource(AVATAR_BG[ci]);
        tvAvatar.setTextColor(AVATAR_FG[ci]);
        tvAvatar.setText(a.name.length() > 0 ? a.name.substring(0, 1) : "?");

        tvName.setText(a.name);
        boolean global = "Global".equals(a.region);
        tvRegion.setText(regionLabel(a.region));
        tvRegion.setTextColor(global ? C_BLUE : C_ACCENT_DEEP);
        tvRegion.setBackgroundResource(global ? R.drawable.bg_chip_blue : R.drawable.bg_chip_mint);

        String sub;
        int subColor = C_DIM;
        if (a.disabled) { sub = getString(R.string.lbl_disabled); subColor = C_FAINT; }
        else if (a.error != null && a.error.length() > 0) { sub = a.error; subColor = C_DANGER; }
        else if (a.expired) { sub = getString(R.string.lbl_cred_expired); subColor = C_DANGER; }
        else if (a.packages > 0) { sub = String.format(getString(R.string.lbl_packages), a.packages); }
        else sub = "";
        tvSub.setText(sub);
        tvSub.setTextColor(subColor);

        if (privacy) {
            tvBalance.setText(MASK);
            tvBalance.setTextColor(C_FAINT);
        } else {
            tvBalance.setText(fmtNum(a.balance));
            tvBalance.setTextColor(a.balance <= 0 ? C_DANGER : C_ACCENT);
        }

        int pct = a.cycleRemainPct();
        if (pct < 0 || a.cycleSize <= 0) {
            boxCycle.setVisibility(View.GONE);
        } else {
            boxCycle.setVisibility(View.VISIBLE);
            tvCycleLeft.setText("周期剩余 " + fmtNum(a.cycleSize - a.cycleUsed) + " / " + fmtNum(a.cycleSize));
            tvCyclePct.setText(pct + "%");
            tvCyclePct.setTextColor(pct >= 40 ? C_ACCENT : (pct >= 15 ? C_WARN : C_DANGER));
            tvCycleEnd.setText(shortDate(a.cycleEnd) + " 重置");
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, pct));
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 100 - pct));
            fill.setBackgroundResource(pct >= 40 ? R.drawable.bg_progress_mint
                    : (pct >= 15 ? R.drawable.bg_progress_warn : R.drawable.bg_progress_danger));
        }

        if (privacy) {
            tvChipToday.setText("今日 " + MASK);
        } else if (staleData) {
            tvChipToday.setText("今日 —");
            tvChipToday.setTextColor(C_FAINT);
            tvChipToday.setBackgroundResource(R.drawable.bg_chip_dim);
        } else {
            tvChipToday.setText("今日 " + fmtNum(a.usedToday));
            tvChipToday.setTextColor(a.usedToday > 0 ? C_DANGER : C_DIM);
            tvChipToday.setBackgroundResource(a.usedToday > 0 ? R.drawable.bg_chip_danger : R.drawable.bg_chip_dim);
        }

        if (a.disabled) {
            tvChipSign.setText(getString(R.string.lbl_disabled));
            tvChipSign.setTextColor(C_FAINT);
            tvChipSign.setBackgroundResource(R.drawable.bg_chip_dim);
        } else if (a.signedToday) {
            tvChipSign.setText("\u2713 已签");
            tvChipSign.setTextColor(C_ACCENT_DEEP);
            tvChipSign.setBackgroundResource(R.drawable.bg_chip_mint);
        } else {
            tvChipSign.setText("未签");
            tvChipSign.setTextColor(C_WARN);
            tvChipSign.setBackgroundResource(R.drawable.bg_chip_warn);
        }

        if (a.expired) {
            tvChipCred.setText("凭证已过期");
            tvChipCred.setTextColor(C_DANGER);
            tvChipCred.setBackgroundResource(R.drawable.bg_chip_danger);
            tvChipCred.setVisibility(View.VISIBLE);
        } else {
            tvChipCred.setVisibility(View.GONE);
        }

        StringBuilder d = new StringBuilder();
        d.append("账号 ID    ").append(a.id.length() > 0 ? a.id : "-").append('\n');
        d.append("周期额度    ").append(fmtNum(a.cycleSize)).append('\n');
        d.append("周期已用    ").append(fmtNum(a.cycleUsed)).append('\n');
        d.append("重置时间    ").append(a.cycleEnd.length() > 0 ? a.cycleEnd : "-").append('\n');
        d.append("套餐数量    ").append(a.packages).append('\n');
        d.append("登录授权    ").append(a.expired ? "已失效，请重新登录 WorkBuddy"
                : (a.expireDays >= 0 ? ("有效（约 " + a.expireDays + " 天）") : "当前有效")).append('\n');
        d.append("今日消耗    ").append(privacy ? MASK : fmtNum(a.usedToday));
        tvDetail.setText(d.toString());

        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                boxDetail.setVisibility(boxDetail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        return v;
    }

    // ==================== 时间线 ====================

    private void renderFlow(Snapshot s) {
        boxFlow.removeAllViews();
        boxFlowAll.removeAllViews();
        List<Snapshot.Flow> f = new ArrayList<Snapshot.Flow>();
        for (Snapshot.Flow item : s.flow) if (item.delta < 0) f.add(item);
        int preview = Math.min(f.size(), 3);
        int all = Math.min(f.size(), 20);
        tvFlowCount.setText(f.size() > 3 ? "查看全部 ›" : (f.size() + " 条"));
        tvFlowAllCount.setText(f.size() > 0 ? ("近 " + all + " 条") : "");
        if (preview == 0) {
            addEmpty(boxFlow, getString(R.string.lbl_no_flow));
            addEmpty(boxFlowAll, getString(R.string.lbl_no_flow));
            return;
        }
        renderDeductionRows(boxFlow, f, preview);
        renderDeductionRows(boxFlowAll, f, all);
    }

    private void renderDeductionRows(LinearLayout target, List<Snapshot.Flow> f, int n) {
        for (int i = 0; i < n; i++) {
            Snapshot.Flow it = f.get(i);
            String right = privacy ? MASK : ("-" + fmtNum(Math.abs(it.delta)));
            // 主文案前标注归属账号（如「某账号 · 自动签到咨询」），
            // 账号名放在最前，标题再长截断也能一眼看出这是哪个账号的变动。
            String main;
            if (it.account.length() > 0) {
                main = it.account + " · " + (it.name.length() > 0 ? it.name : "积分变动");
            } else {
                main = it.name.length() > 0 ? it.name : "积分变动";
            }
            addRow(target, R.drawable.dot_down, main, fmtDateTime(it.ts), right,
                    privacy ? C_TEXT : C_DANGER);
        }
    }

    private void renderRecent(Snapshot s) {
        boxRecent.removeAllViews();
        List<Snapshot.Task> done = new ArrayList<Snapshot.Task>();
        for (Snapshot.Task t : s.tasks) if (!t.isActive()) done.add(t);
        int n = Math.min(done.size(), 8);
        tvRecentCount.setText(done.size() > 0 ? ("近 " + n + " 条") : "");
        if (n == 0) {
            addEmpty(boxRecent, "暂无历史任务");
            return;
        }
        for (int i = 0; i < n; i++) {
            Snapshot.Task t = done.get(i);
            int dot = R.drawable.dot_done;
            int col = C_DIM;
            if ("failed".equals(t.state)) { dot = R.drawable.dot_off; col = C_DANGER; }
            else if ("running".equals(t.state)) { dot = R.drawable.dot_live; col = C_ACCENT; }
            else if ("waiting".equals(t.state)) { dot = R.drawable.dot_waiting; col = C_WARN; }
            String right;
            if (t.cost > 0 && privacy) right = MASK;
            else if (t.cost > 0) right = "-" + fmtNum(t.cost);
            else right = "";
            addRow(boxRecent, dot,
                    t.title.length() > 0 ? t.title : "(无标题任务)",
                    stateLabel(t.state) + " · " + t.meta, right, col);
        }
    }

    private String stateLabel(String st) {
        if ("running".equals(st)) return getString(R.string.st_running);
        if ("waiting".equals(st)) return getString(R.string.st_waiting);
        if ("done".equals(st)) return getString(R.string.st_done);
        if ("failed".equals(st)) return getString(R.string.st_failed);
        if ("stopped".equals(st)) return getString(R.string.st_stopped);
        return getString(R.string.st_idle);
    }

    // ==================== 通用小部件 ====================

    private void addRow(LinearLayout box, int dotRes, String main, String sub, String right, int rightColor) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_row, box, false);
        View dot = v.findViewById(R.id.vDot);
        TextView m = v.findViewById(R.id.tvMain);
        TextView sb = v.findViewById(R.id.tvSub);
        TextView r = v.findViewById(R.id.tvRight);
        dot.setBackgroundResource(dotRes);
        m.setText(main);
        sb.setText(sub);
        r.setText(right);
        r.setTextColor(rightColor);
        box.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addEmpty(LinearLayout box, String text) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_empty, box, false);
        ((TextView) v.findViewById(R.id.tvText)).setText(text);
        if (box == boxFlow || box == boxFlowAll || box == boxRecent) v.setBackgroundResource(R.color.transparent);
        box.addView(v);
    }

    private void gap(LinearLayout box, int dpv) {
        box.addView(new View(this), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(dpv)));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String money(double v) {
        return privacy ? MASK : fmtNum(v);
    }

    private void updateFresh() {
        if (dataTs <= 0) { tvFresh.setText(""); return; }
        long d = System.currentTimeMillis() - dataTs;
        if (d < 0) d = 0;
        long sec = d / 1000L;
        String t;
        if (sec < 10) t = getString(R.string.lbl_just_now);
        else if (sec < 60) t = sec + " 秒前";
        else if (sec < 3600) t = (sec / 60) + " 分钟前";
        else if (sec < 86400) t = (sec / 3600) + " 小时前";
        else t = (sec / 86400) + " 天前";
        tvFresh.setText("· " + t);
        tvFresh.setTextColor(sec > 600 ? C_WARN : C_FAINT);
    }

    // ==================== 工具 ====================

    static String fmtNum(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) return String.format(Locale.US, "%.2fM", v / 1_000_000d);
        if (a >= 10_000) return String.format(Locale.US, "%.1fk", v / 1000d);
        if (v == (long) v) return String.format(Locale.US, "%d", (long) v);
        return String.format(Locale.US, "%.2f", v);
    }

    static String fmtTime(long ts) {
        if (ts <= 0) return "--:--";
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ts));
    }

    /** "N 秒前 / N 分钟前 / N 小时前"（主界面与小组件共用，保证过期措辞一致） */
    static String fmtAgo(long ms) {
        if (ms < 0) ms = 0;
        long sec = ms / 1000L;
        if (sec < 60) return sec + " 秒前";
        if (sec < 3600) return (sec / 60) + " 分钟前";
        if (sec < 86400) return (sec / 3600) + " 小时前";
        return (sec / 86400) + " 天前";
    }

    static String fmtDateTime(long ts) {
        if (ts <= 0) return "--";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ts));
    }

    static String shortDate(String s) {
        if (s == null || s.length() < 10) return "-";
        return s.substring(5, 10);
    }

    static String regionLabel(String r) {
        if ("Global".equals(r)) return "Global";
        return "CN";
    }
}
