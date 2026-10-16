package io.github.workbuddymonitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 多账号数据模型 + JSON 解析。
 *
 * 伴侣服务返回结构（多账号 + 本机任务）：
 * {
 *   "ok":true,"ts":...,"online":true,
 *   "accounts":[{id,name,region,balance,usedToday,cycleSize,cycleUsed,cycleEnd,
 *                signedToday,disabled,expired,error,packages}],
 *   "totalBalance":..,"totalUsedToday":..,"accountCount":..,
 *   "activeCount":..,"signedCount":..,"cloud":true,
 *   "tasks":[{id,title,state,progress,meta,cost,lastAgeMs}],
 *   "running":2,"waiting":0,"flow":[{ts,name,delta,account}],
 *   "totalUsed":..,"usedMonth":..,"taskErr":""
 * }
 */
public class Snapshot {

    public boolean ok;
    public long ts;
    public boolean online;
    public List<Account> accounts = new ArrayList<Account>();
    public double totalBalance;
    public double totalUsedToday;
    public int accountCount;
    public int activeCount;
    public int signedCount;
    public boolean cloud;

    // ---- 本机执行任务 / 流水 ----
    public List<Task> tasks = new ArrayList<Task>();
    public List<Flow> flow = new ArrayList<Flow>();
    public int running;
    public int waiting;
    public double totalUsed;
    public double usedMonth;
    public String taskErr = "";

    // ================= 账号 =================
    public static class Account {
        public String id = "";
        public String name = "";
        public String region = "CN";
        public double balance;
        public boolean balanceIsTotal;
        public double usedToday;
        public double cycleSize;
        public double cycleUsed;
        public String cycleEnd = "";
        public boolean signedToday;
        public boolean disabled;
        public boolean expired;
        public String error = "";
        public int packages;
        /** 凭证剩余天数（-1 = 未知） */
        public int expireDays = -1;

        /** 周期剩余百分比（0~100）；拿不到周期数据时返回 -1 */
        public int cycleRemainPct() {
            if (cycleSize <= 0) return -1;
            double p = (cycleSize - cycleUsed) / cycleSize * 100.0;
            if (p < 0) p = 0;
            if (p > 100) p = 100;
            return (int) Math.round(p);
        }

        public static Account from(JSONObject o) {
            Account a = new Account();
            a.id = o.optString("id", "");
            a.name = o.optString("name", "");
            a.region = o.optString("region", "CN");
            a.balance = o.optDouble("balance", 0d);
            a.balanceIsTotal = o.optBoolean("balanceIsTotal", false);
            a.usedToday = o.optDouble("usedToday", 0d);
            a.cycleSize = o.optDouble("cycleSize", 0d);
            a.cycleUsed = o.optDouble("cycleUsed", 0d);
            a.cycleEnd = o.optString("cycleEnd", "");
            a.signedToday = o.optBoolean("signedToday", false);
            a.disabled = o.optBoolean("disabled", false);
            a.expired = o.optBoolean("expired", false);
            a.error = o.optString("error", "");
            a.packages = o.optInt("packages", 0);
            a.expireDays = o.optInt("expireDays", -1);
            return a;
        }

        public JSONObject toJson() throws Exception {
            JSONObject x = new JSONObject();
            x.put("id", id); x.put("name", name); x.put("region", region);
            x.put("balance", balance); x.put("usedToday", usedToday);
            x.put("cycleSize", cycleSize); x.put("cycleUsed", cycleUsed);
            x.put("cycleEnd", cycleEnd); x.put("signedToday", signedToday);
            x.put("disabled", disabled); x.put("expired", expired);
            x.put("error", error); x.put("packages", packages);
            x.put("expireDays", expireDays);
            return x;
        }
    }

    // ================= 任务 =================
    public static class Task {
        public String id = "";
        public String title = "";
        public String state = "idle";   // running / waiting / done / failed / stopped / idle
        public int progress;
        public String meta = "";
        public double cost;
        public long lastAgeMs;

        public boolean isActive() { return "running".equals(state) || "waiting".equals(state); }

        public static Task from(JSONObject o) {
            Task t = new Task();
            t.id = o.optString("id", "");
            t.title = o.optString("title", "");
            t.state = o.optString("state", "idle");
            t.progress = o.optInt("progress", 0);
            t.meta = o.optString("meta", "");
            t.cost = o.optDouble("cost", 0d);
            t.lastAgeMs = o.optLong("lastAgeMs", 0L);
            return t;
        }

        public JSONObject toJson() throws Exception {
            JSONObject x = new JSONObject();
            x.put("id", id); x.put("title", title); x.put("state", state);
            x.put("progress", progress); x.put("meta", meta);
            x.put("cost", cost); x.put("lastAgeMs", lastAgeMs);
            return x;
        }
    }

    // ================= 流水 =================
    public static class Flow {
        public long ts;
        public String name = "";
        public double delta;
        /** 归属账号显示名（桥接端按 session→user_id→凭证 推导，数据驱动；未来加账号无需改代码） */
        public String account = "";

        public static Flow from(JSONObject o) {
            Flow f = new Flow();
            f.ts = o.optLong("ts", 0L);
            f.name = o.optString("name", "");
            f.delta = o.optDouble("delta", 0d);
            f.account = o.optString("account", "");
            return f;
        }

        public JSONObject toJson() throws Exception {
            JSONObject x = new JSONObject();
            x.put("ts", ts); x.put("name", name); x.put("delta", delta);
            x.put("account", account);
            return x;
        }
    }

    // ================= 解析 / 序列化 =================
    public static Snapshot fromJson(String s) {
        Snapshot r = new Snapshot();
        try {
            JSONObject o = new JSONObject(s);
            r.ok = o.optBoolean("ok", true);
            r.ts = o.optLong("ts", System.currentTimeMillis());
            r.online = o.optBoolean("online", true);
            r.totalBalance = o.optDouble("totalBalance", 0d);
            r.totalUsedToday = o.optDouble("totalUsedToday", 0d);
            r.accountCount = o.optInt("accountCount", 0);
            r.activeCount = o.optInt("activeCount", 0);
            r.signedCount = o.optInt("signedCount", 0);
            r.cloud = o.optBoolean("cloud", false);

            r.running = o.optInt("running", 0);
            r.waiting = o.optInt("waiting", 0);
            r.totalUsed = o.optDouble("totalUsed", 0d);
            r.usedMonth = o.optDouble("usedMonth", 0d);
            r.taskErr = o.optString("taskErr", "");

            JSONArray aa = o.optJSONArray("accounts");
            if (aa != null) for (int i = 0; i < aa.length(); i++) r.accounts.add(Account.from(aa.getJSONObject(i)));

            JSONArray ta = o.optJSONArray("tasks");
            if (ta != null) for (int i = 0; i < ta.length(); i++) r.tasks.add(Task.from(ta.getJSONObject(i)));

            JSONArray fa = o.optJSONArray("flow");
            if (fa != null) for (int i = 0; i < fa.length(); i++) r.flow.add(Flow.from(fa.getJSONObject(i)));
        } catch (Exception e) {
            r.ok = false;
        }
        return r;
    }

    /** 缓存用（小组件离线时也能显示任务） */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("ts", ts);
            o.put("online", online);
            o.put("totalBalance", totalBalance);
            o.put("totalUsedToday", totalUsedToday);
            o.put("accountCount", accountCount);
            o.put("activeCount", activeCount);
            o.put("signedCount", signedCount);
            o.put("cloud", cloud);
            o.put("running", running);
            o.put("waiting", waiting);
            o.put("totalUsed", totalUsed);
            o.put("usedMonth", usedMonth);
            o.put("taskErr", taskErr);

            JSONArray aa = new JSONArray();
            for (Account a : accounts) aa.put(a.toJson());
            o.put("accounts", aa);

            JSONArray ta = new JSONArray();
            for (Task t : tasks) ta.put(t.toJson());
            o.put("tasks", ta);

            JSONArray fa = new JSONArray();
            for (Flow f : flow) fa.put(f.toJson());
            o.put("flow", fa);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static Snapshot fromCache(String s) {
        if (s == null || s.length() == 0) return null;
        return fromJson(s);
    }

    /** 正在执行的任务（运行中优先，其次等待） */
    public List<Task> activeTasks() {
        List<Task> out = new ArrayList<Task>();
        for (Task t : tasks) if ("running".equals(t.state)) out.add(t);
        for (Task t : tasks) if ("waiting".equals(t.state)) out.add(t);
        return out;
    }

    /** 快照年龄（毫秒）。负数（手机与电脑时钟有偏差）按 0 计，避免误判为"未来数据"。 */
    public long ageMs() {
        long d = System.currentTimeMillis() - ts;
        return d < 0 ? 0 : d;
    }

    /**
     * 数据是否已过期。
     *
     * 设计要点：过期数据绝不允许被当成实时值展示。
     * 历史事故——旧版本伴侣服务的快照被缓存后长期渲染，界面显示"今日消耗 35.2"，
     * 而真实值是 102.94，用户据此认为"数字是错的、监控不可信"。
     * 宁可显示"—"或"数据已过期 N 分钟前"，也不显示一个看起来正常却错误的数字。
     */
    public boolean isStale(long maxMs) {
        return ts <= 0 || ageMs() > maxMs;
    }

    /** 最近一个非活动任务标题（空闲时小组件也有真实内容可显示） */
    public String lastDoneTitle() {
        for (Task t : tasks) {
            if (!"running".equals(t.state) && !"waiting".equals(t.state)
                    && t.title != null && t.title.length() > 0) {
                return t.title;
            }
        }
        return "";
    }
}
