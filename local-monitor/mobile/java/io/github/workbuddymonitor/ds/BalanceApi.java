package io.github.workbuddymonitor.ds;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * DeepSeek 官方余额接口。
 *
 * GET https://api.deepseek.com/user/balance
 * Authorization: Bearer &lt;api key&gt;
 * → {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00",
 *     "granted_balance":"10.00","topped_up_balance":"100.00"}]}
 *
 * 说明：官方只提供余额，没有任何用量接口，因此"消耗"必须由本应用采样差分得出。
 * 本类与 WorkBuddy 积分数据完全无关，手机直连 api.deepseek.com，不经过电脑端 bridge。
 */
public final class BalanceApi {

    public static final String ENDPOINT = "https://api.deepseek.com/user/balance";
    private static final String UA = "WorkBuddyMonitor/DS (Android)";

    public static class Result {
        public boolean ok;
        public int http;
        public String error;
        public Sample sample;
    }

    private BalanceApi() {}

    public static Result fetch(String apiKey) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", UA);
            r.http = c.getResponseCode();

            InputStream in = (r.http >= 200 && r.http < 300) ? c.getInputStream() : c.getErrorStream();
            String body = readFully(in);

            if (r.http == 200) {
                parse(r, body);
            } else {
                r.ok = false;
                r.error = humanError(r.http, body);
            }
        } catch (Exception e) {
            r.ok = false;
            r.error = friendly(e);
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Exception ignored) {}
            }
        }
        return r;
    }

    private static void parse(Result r, String body) {
        try {
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("balance_infos");
            if (arr == null || arr.length() == 0) {
                r.ok = false;
                r.error = "接口未返回余额信息";
                return;
            }
            // 优先取 CNY，其次第一条
            JSONObject pick = arr.optJSONObject(0);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it != null && "CNY".equalsIgnoreCase(it.optString("currency"))) {
                    pick = it;
                    break;
                }
            }
            if (pick == null) {
                r.ok = false;
                r.error = "接口返回格式异常";
                return;
            }
            Sample s = new Sample();
            s.ts = System.currentTimeMillis();
            s.total = num(pick.optString("total_balance", "0"));
            s.granted = num(pick.optString("granted_balance", "0"));
            s.toppedUp = num(pick.optString("topped_up_balance", "0"));
            s.currency = pick.optString("currency", "CNY");
            r.sample = s;
            r.ok = true;
        } catch (Exception e) {
            r.ok = false;
            r.error = "解析余额失败：" + e.getMessage();
        }
    }

    private static double num(String v) {
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return 0d; }
    }

    private static String humanError(int http, String body) {
        String msg = "";
        try {
            JSONObject o = new JSONObject(body);
            JSONObject e = o.optJSONObject("error");
            if (e != null) msg = e.optString("message", "");
            else msg = o.optString("message", "");
        } catch (Exception ignored) {
            msg = body == null ? "" : body.trim();
        }
        if (msg.length() > 160) msg = msg.substring(0, 160) + "…";
        switch (http) {
            case 401:
                return "API Key 无效或已被撤销（401）" + (msg.isEmpty() ? "" : "\n" + msg);
            case 402:
                return "账户余额不足（402）" + (msg.isEmpty() ? "" : "\n" + msg);
            case 429:
                return "请求过于频繁，已被限流（429）";
            case 500:
            case 502:
            case 503:
                return "DeepSeek 服务暂时不可用（" + http + "）";
            default:
                return "请求失败 HTTP " + http + (msg.isEmpty() ? "" : "\n" + msg);
        }
    }

    private static String friendly(Exception e) {
        String n = e.getClass().getSimpleName();
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (n.contains("UnknownHost") || n.contains("ConnectException")) return "网络不可达，请检查手机网络";
        if (n.contains("SocketTimeout")) return "请求超时，DeepSeek 或网络较慢";
        if (n.contains("SSL")) return "TLS 握手失败：" + m;
        return n + "：" + m;
    }

    private static String readFully(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
