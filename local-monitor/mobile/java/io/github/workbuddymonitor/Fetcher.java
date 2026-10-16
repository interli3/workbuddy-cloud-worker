package io.github.workbuddymonitor;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Fetcher {

    private static final String TAG = "wbmon";

    public interface Cb {
        void onResult(Snapshot s, String err);
    }

    public static void fetch(final Context c, final Cb cb) {
        new Thread(new Runnable() {
            public void run() {
                final Snapshot[] out = new Snapshot[1];
                final String[] err = new String[1];
                StringBuilder log = new StringBuilder();

                List<String> bases = Store.candidates(c);
                boolean ok = false;
                String ch = "";
                for (int i = 0; i < bases.size(); i++) {
                    String base = bases.get(i);
                    if (tryOne(c, base, out, err)) { ok = true; ch = shortName(base); break; }
                    if (log.length() > 0) log.append("\n");
                    log.append("· ").append(shortName(base)).append("：").append(err[0]);
                }

                // MQTT 会合点：人在外面也无需知道电脑地址，关机时也能拿到最后快照
                if (!ok) {
                    if (tryMqtt(c, out, err)) {
                        ok = true;
                        ch = "云端会合点";
                    } else {
                        if (log.length() > 0) log.append("\n");
                        log.append("· 云端会合点：").append(err[0]);
                    }
                }

                if (!ok) {
                    // 全部通道失败 → 回退到上次成功缓存。
                    // 注意：这里返回的是"过期快照"，调用方必须按过期数据处理
                    //（界面/小组件会显式标注数据年龄），绝不能当成实时值展示。
                    String cached = Store.cache(c);
                    if (cached != null && cached.length() > 0) {
                        Snapshot s = Snapshot.fromCache(cached);
                        if (s != null) {
                            s.online = false;
                            out[0] = s;
                        }
                    }
                    err[0] = log.toString();
                } else {
                    report(c, ch);
                }

                if (cb != null) cb.onResult(out[0], ok ? null : err[0]);
            }
        }).start();
    }

    private static String shortName(String base) {
        if (base.contains("trycloudflare.com")) return "公网通道";
        if (base.contains("192.168.") || base.contains("10.") || base.contains("172.")) return "局域网";
        return base;
    }

    /**
     * 向电脑端上报"我装的是哪个版本、走哪条通道、什么时候拉的"。
     *
     * 为什么要这一步：电脑端无法反问手机装了哪个 APK，导致"以为升级了其实没升"
     * 只能靠人反复确认。上报后电脑端 /status 直接能看到手机版本与最后在线时间，
     * 排障从"猜"变成"看"。节流 5 分钟，失败静默，不影响主流程。
     */
    private static void report(Context c, String channel) {
        try {
            long now = System.currentTimeMillis();
            if (now - Store.lastReport(c) < 300_000L) return;
            Store.setLastReport(c, now);
            String payload = "{\"ver\":\"" + Store.appVer(c) + "\",\"ts\":" + now
                    + ",\"ch\":\"" + channel + "\"}";
            Mqtt.publish(Store.MQTT_HOST, Store.MQTT_PORT, Store.MQTT_TOPIC_APP, payload);
        } catch (Throwable ignore) {
        }
    }

    /** MQTT 会合点通道：取电脑推上来的最新快照。拿到即成功，用时间戳判断是否实时 */
    private static boolean tryMqtt(Context c, Snapshot[] out, String[] err) {
        try {
            // 公共 broker 的 retained 消息并非始终可靠；电脑每 15 秒主动发布一次，
            // 监听窗口必须覆盖完整周期，否则外网刷新会随机错过下一帧。
            String payload = Mqtt.pull(Store.MQTT_HOST, Store.MQTT_PORT, Store.MQTT_TOPIC, 20000);
            if (payload == null || payload.length() == 0) {
                err[0] = "无数据（电脑可能关机）";
                return false;
            }
            Snapshot s = Snapshot.fromJson(payload);
            if (s == null) { err[0] = "解析失败"; return false; }
            long age = System.currentTimeMillis() - s.ts;
            s.online = age < 90_000L;   // 90 秒内的快照视为电脑在线（实时）
            out[0] = s;
            Store.setCache(c, payload);
            if (s.online) Store.setLastOk(c, System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            err[0] = e.getClass().getSimpleName();
            return false;
        }
    }

    /** 尝试单个地址，成功返回 true */
    private static boolean tryOne(Context c, String base, Snapshot[] out, String[] err) {
        HttpURLConnection con = null;
        boolean isLan = base.startsWith("http://192.168.") || base.startsWith("http://10.")
                || base.startsWith("http://172.");
        try {
            URL u = new URL(base + "/snapshot");
            con = (HttpURLConnection) u.openConnection();
            con.setConnectTimeout(isLan ? 2500 : 8000);
            con.setReadTimeout(isLan ? 3500 : 9000);
            con.setRequestMethod("GET");
            con.setRequestProperty("Accept", "application/json");
            // 自报版本：电脑端据此判断手机装的是不是最新版（排障关键）
            con.setRequestProperty("X-Wbmon-Ver", Store.appVer(c));
            String tk = Store.token(c);
            if (tk != null && tk.length() > 0)
                con.setRequestProperty("Authorization", "Bearer " + tk);

            int code = con.getResponseCode();
            if (code != 200) {
                err[0] = "HTTP " + code;
                return false;
            }
            InputStream is = con.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            String json = sb.toString();
            Snapshot s = Snapshot.fromJson(json);
            if (s == null) { err[0] = "数据解析失败"; return false; }
            s.online = true;
            out[0] = s;
            Store.setCache(c, json);
            Store.setLastOk(c, System.currentTimeMillis());
            return true;
        } catch (UnknownHostException e) {
            err[0] = "找不到主机";
        } catch (ConnectException e) {
            err[0] = "连接被拒";
        } catch (SocketTimeoutException e) {
            err[0] = "超时";
        } catch (javax.net.ssl.SSLException e) {
            err[0] = "SSL 错误";
        } catch (Exception e) {
            err[0] = e.getClass().getSimpleName();
            Log.w(TAG, "fetch failed: " + base, e);
        } finally {
            if (con != null) con.disconnect();
        }
        return false;
    }

    public static String post(String urlStr, String body, String token) throws Exception {
        HttpURLConnection con = null;
        try {
            URL u = new URL(urlStr);
            con = (HttpURLConnection) u.openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(8000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            if (token != null && token.length() > 0)
                con.setRequestProperty("Authorization", "Bearer " + token);
            OutputStream os = con.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = con.getResponseCode();
            InputStream is = code == 200 ? con.getInputStream() : con.getErrorStream();
            if (is == null) return "HTTP " + code;
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return sb.toString();
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
