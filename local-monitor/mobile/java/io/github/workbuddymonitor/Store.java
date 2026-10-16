package io.github.workbuddymonitor;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class Store {
    private static final String P = "wbmon";
    private static final String K_HOST = "host";
    private static final String K_LAN = "lan";
    private static final String K_TOKEN = "token";
    private static final String K_INTERVAL = "interval";
    private static final String K_CACHE = "cache";
    private static final String K_LAST_OK = "lastOk";
    private static final String K_CFGVER = "cfgver";
    private static final String K_PRIVACY = "privacy";
    private static final String K_REPORT = "lastReport";

    /** 配置版本：升级 APK 时 +1，可强制把地址重置为打包时内置的最新地址 */
    private static final int CFG_VER = 7;

    /** 兜底版本号（正常从安装包读取；两者需与 AndroidManifest 同步） */
    public static final String APP_VER = "3.8.0";

    /**
     * 数据新鲜度阈值（毫秒）：超过这个年龄的快照一律视为"过期"。
     * 关键设计——过期数据绝不允许被当作实时数据展示，
     * 否则用户会看到与真实完全不符的数字（历史事故：界面显示 35.2，真实 102.94）。
     */
    public static long staleMs(android.content.Context c) {
        long v = interval(c) * 3;
        return v < 180_000L ? 180_000L : v;   // 至少 3 分钟
    }

    /** App 版本：优先读安装包真实版本，失败回退常量。用于向电脑端上报。 */
    public static String appVer(android.content.Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return APP_VER;
        }
    }

    /**
     * 多账号版主通道是 MQTT 会合点（无需地址、关机也留最后快照）。
     * 公网隧道仅作兜底，默认留空（在外自动走 MQTT）。如需隧道兜底，
     * 在设置里填 trycloudflare 地址即可。
     */
    public static final String DEFAULT_HOST = "";

    /** 局域网地址——在家同一 WiFi 直连，速度快 */
    public static final String DEFAULT_LAN = LocalConfig.DEFAULT_LAN;

    /** MQTT 会合点：电脑把快照推到公共 broker，手机去取，无需知道电脑地址 */
    public static final String MQTT_HOST = LocalConfig.MQTT_HOST;
    public static final int MQTT_PORT = LocalConfig.MQTT_PORT;
    public static final String MQTT_TOPIC = LocalConfig.MQTT_TOPIC;

    /** 与 WorkBuddy 登录令牌无关；仅用于保护电脑端局域网快照接口。 */
    public static final String BRIDGE_TOKEN = LocalConfig.BRIDGE_TOKEN;

    /** 手机 → 电脑 的状态回报主题（让电脑端能确认手机版本与最后在线时间） */
    public static final String MQTT_TOPIC_APP = MQTT_TOPIC + "/app";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static String host(Context c) {
        String h = sp(c).getString(K_HOST, "");
        return (h == null || h.length() == 0) ? DEFAULT_HOST : h;
    }

    public static void setHost(Context c, String v) { sp(c).edit().putString(K_HOST, v).apply(); }

    /** 局域网地址（在家直连，快） */
    public static String lanHost(Context c) {
        String h = sp(c).getString(K_LAN, "");
        return (h == null || h.length() == 0) ? DEFAULT_LAN : h;
    }

    public static void setLanHost(Context c, String v) { sp(c).edit().putString(K_LAN, v).apply(); }

    /**
     * 候选地址列表，按顺序尝试：先局域网（在家中毫秒级响应），
     * 失败再走公网隧道（人在外面）。全部失败才报错。
     */
    public static List<String> candidates(Context c) {
        List<String> out = new ArrayList<String>();
        addNorm(out, lanHost(c));
        addNorm(out, host(c));
        return out;
    }

    private static void addNorm(List<String> list, String raw) {
        if (raw == null) return;
        String h = raw.trim();
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        if (h.length() < 8) return;
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://" + h;
        if (!list.contains(h)) list.add(h);
    }

    /**
     * 升级安装后自动纠正配置：配置版本变化时，把公网/局域网地址重置为打包时内置的最新值，
     * 并清掉上一次的数据缓存。
     *
     * 清缓存是必须的：SharedPreferences 跨版本存活，旧版本（甚至旧版伴侣服务）
     * 写进去的快照会被新版本当成"上次成功数据"继续渲染，
     * 于是界面长期显示一份与真实完全不符的旧数字（历史事故：35.2 vs 102.94）。
     * 宁可短暂显示"未连接"，也不显示一个假的实时值。
     */
    public static void migrate(Context c) {
        SharedPreferences s = sp(c);
        if (s.getInt(K_CFGVER, 0) >= CFG_VER) return;
        SharedPreferences.Editor e = s.edit()
                .putString(K_HOST, DEFAULT_HOST)
                .putString(K_LAN, DEFAULT_LAN)
                .remove(K_CACHE)
                .putInt(K_CFGVER, CFG_VER);
        if (s.getString(K_TOKEN, "").length() == 0 && BRIDGE_TOKEN.length() > 0)
            e.putString(K_TOKEN, BRIDGE_TOKEN);
        e.apply();
    }

    public static String token(Context c) { return sp(c).getString(K_TOKEN, BRIDGE_TOKEN); }

    public static void setToken(Context c, String v) { sp(c).edit().putString(K_TOKEN, v).apply(); }

    /** 自动刷新间隔（毫秒） */
    public static long interval(Context c) {
        long v = sp(c).getLong(K_INTERVAL, 30_000L);
        return v < 15_000L ? 15_000L : v;
    }

    public static void setInterval(Context c, long v) { sp(c).edit().putLong(K_INTERVAL, v).apply(); }

    public static void setIntervalIndex(Context c, int idx) {
        long[] vals = {15_000L, 30_000L, 60_000L, 3 * 60_000L, 10 * 60_000L};
        if (idx >= 0 && idx < vals.length) setInterval(c, vals[idx]);
    }

    public static int intervalIndex(Context c) {
        long v = interval(c);
        if (v <= 15_000L) return 0;
        if (v <= 30_000L) return 1;
        if (v <= 60_000L) return 2;
        if (v <= 3 * 60_000L) return 3;
        return 4;
    }

    /** 返回 /snapshot 的完整地址 */
    public static String snapshotUrl(Context c) {
        String h = host(c).trim();
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h + "/snapshot";
    }

    public static String cache(Context c) { return sp(c).getString(K_CACHE, ""); }

    public static void setCache(Context c, String json) { sp(c).edit().putString(K_CACHE, json).apply(); }

    public static long lastOk(Context c) { return sp(c).getLong(K_LAST_OK, 0L); }

    public static void setLastOk(Context c, long v) { sp(c).edit().putLong(K_LAST_OK, v).apply(); }

    /** 上次向电脑端上报态的时间（上报节流用） */
    public static long lastReport(Context c) { return sp(c).getLong(K_REPORT, 0L); }

    public static void setLastReport(Context c, long v) { sp(c).edit().putLong(K_REPORT, v).apply(); }

    /** 隐私模式：隐藏所有金额（地铁/公共场合防窥） */
    public static boolean privacy(Context c) { return sp(c).getBoolean(K_PRIVACY, false); }

    public static void setPrivacy(Context c, boolean v) { sp(c).edit().putBoolean(K_PRIVACY, v).apply(); }
}
