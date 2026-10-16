package io.github.workbuddymonitor.ds;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 由采样序列推导"消耗"。
 *
 * 核心口径：余额下降 = 消耗；余额上升 = 充值/赠金到账（不计入消耗）。
 * 官方没有用量接口，这是唯一诚实的算法，代价是消耗会在两次采样之间被"聚合"。
 */
public final class Stats {

    /** 样本跨度不足这个时长时，不给出"日均速率 / 可用天数"，避免线性外推的假精度。 */
    public static final long MIN_SPAN_FOR_RATE = 6 * 3600_000L;

    public static class Spend {
        public long ts;
        public double amount;
        public double from;
        public double to;

        public String time(long t) {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(t));
        }
    }

    public double current;
    public double granted;
    public double toppedUp;
    public String currency = "CNY";

    public double todaySpend;
    public double yesterdaySpend;
    public double monthSpend;
    public double weekSpend;

    public double[] last7 = new double[7];   // [0]=6 天前 … [6]=今天
    public double[] last7TopUp = new double[7];

    public double burnPerDay;                // 近 7 天日均消耗（不足 7 天按实际跨度）
    public double burnPerHour;               // 近 24 小时速率
    public int daysLeft = -1;                // 按近 7 天日均估算的可用天数
    public long lastTs;
    public long lastOkTs;
    public long historySpanMs;               // 采样历史总跨度
    public boolean rateReliable;             // 速率/可用天数是否可信
    public boolean hasData;
    public boolean hasKey;
    public String lastError;

    public final List<Spend> recent = new ArrayList<>();   // 最近消耗，倒序
    public double totalSpendAll;                           // 历史累计消耗
    public double totalTopUpAll;

    public static Stats compute(Context c, DsPrefs p) {
        Stats st = new Stats();
        List<Sample> list = DsStore.load(c);
        st.hasKey = p.hasKey();
        st.lastError = p.getLastError();
        if (list.isEmpty()) {
            st.currency = p.getCurrency();
            return st;
        }
        st.hasData = true;
        Sample last = list.get(list.size() - 1);
        st.current = last.total;
        st.granted = last.granted;
        st.toppedUp = last.toppedUp;
        st.currency = last.currency == null ? "CNY" : last.currency;
        st.lastTs = last.ts;
        st.lastOkTs = last.ts;

        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long today0 = cal.getTimeInMillis();
        long yesterday0 = today0 - 86400000L;
        cal.set(Calendar.DAY_OF_MONTH, 1);
        long month0 = cal.getTimeInMillis();
        long week0 = today0 - 6 * 86400000L;

        List<Spend> spends = new ArrayList<>();
        Sample prev = null;
        for (Sample s : list) {
            if (prev != null) {
                double delta = prev.total - s.total;
                if (delta > 0.00005d) {
                    Spend sp = new Spend();
                    sp.ts = s.ts;
                    sp.amount = delta;
                    sp.from = prev.total;
                    sp.to = s.total;
                    spends.add(sp);
                } else if (delta < -0.00005d) {
                    st.totalTopUpAll += -delta;
                }
            }
            prev = s;
        }

        for (Spend sp : spends) {
            st.totalSpendAll += sp.amount;
            if (sp.ts >= today0) st.todaySpend += sp.amount;
            else if (sp.ts >= yesterday0) st.yesterdaySpend += sp.amount;
            if (sp.ts >= month0) st.monthSpend += sp.amount;
            if (sp.ts >= week0) st.weekSpend += sp.amount;
            int idx = (int) ((sp.ts - week0) / 86400000L);
            if (idx >= 0 && idx < 7) st.last7[idx] += sp.amount;
        }

        // 最近消耗明细（倒序，最多 200 条）
        for (int i = spends.size() - 1; i >= 0 && st.recent.size() < 200; i--) {
            st.recent.add(spends.get(i));
        }

        // 速率：优先用近 7 天窗口，跨度不足时用实际跨度
        long spanStart = Math.max(list.get(0).ts, week0);
        long historySpan = Math.max(0L, now - list.get(0).ts);
        st.historySpanMs = historySpan;
        double spanDays = Math.max(1d / 24d, (now - spanStart) / 86400000d);
        st.burnPerDay = st.weekSpend / spanDays;
        if (spanDays < 1 && st.weekSpend <= 0) st.burnPerDay = 0;

        // 近 24 小时速率
        double h24 = 0;
        for (Spend sp : spends) if (sp.ts >= now - 86400000L) h24 += sp.amount;
        st.burnPerHour = h24 / 24d;

        /*
         * 关键口径：样本跨度太短时，任何"日均速率/可用天数"都是噪声。
         * 例如只有 2 分钟样本、花了 0.16 元，线性外推会得出"日耗 3.84 元、还够 5 天"这种吓人的假结论。
         * 因此不足 MIN_SPAN_FOR_RATE 时一律不给速率与天数，只在界面上提示"样本不足"。
         */
        if (historySpan < MIN_SPAN_FOR_RATE) {
            st.burnPerDay = 0;
            st.burnPerHour = 0;
            st.daysLeft = -1;
            st.rateReliable = false;
            return st;
        }
        st.rateReliable = true;
        double rate = st.burnPerDay > 0 ? st.burnPerDay : st.burnPerHour * 24d;
        st.daysLeft = rate > 0.0001d ? (int) Math.floor(st.current / rate) : -1;
        return st;
    }

    public static String money(String currency, double v) {
        String sym = "USD".equalsIgnoreCase(currency) ? "$" : "¥";
        return sym + String.format(java.util.Locale.CHINA, "%.2f", v);
    }

    public static String moneyShort(String currency, double v) {
        String sym = "USD".equalsIgnoreCase(currency) ? "$" : "¥";
        if (Math.abs(v) >= 1000) return sym + String.format(java.util.Locale.CHINA, "%.1fk", v / 1000d);
        return sym + String.format(java.util.Locale.CHINA, "%.2f", v);
    }

    public String currencySymbol() {
        return "USD".equalsIgnoreCase(currency) ? "$" : "¥";
    }
}
