package io.github.workbuddymonitor.ds;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * DeepSeek 采样历史持久化：filesDir/ds_samples.json（纯文本 JSON，便于用户备份与排查）。
 *
 * 与宿主 WorkBuddy 的缓存分开存放，互不影响：宿主用 SharedPreferences("wbmon") 存快照，
 * 本类只用独立文件存 DeepSeek 余额采样序列。
 */
public final class DsStore {

    private static final String FILE = "ds_samples.json";
    private static final int MAX_SAMPLES = 20000;         // 约 15 分钟采样 × 200 天
    private static final long KEEP_MILLIS = 200L * 86400000L;

    private DsStore() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    public static synchronized List<Sample> load(Context c) {
        List<Sample> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            if (n <= 0) return out;
            JSONArray arr = new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Sample s = new Sample();
                s.ts = o.optLong("t");
                s.total = o.optDouble("v", 0);
                s.granted = o.optDouble("g", 0);
                s.toppedUp = o.optDouble("u", 0);
                s.currency = o.optString("c", "CNY");
                out.add(s);
            }
        } catch (Exception ignored) {
            // 文件损坏时退回空历史，不阻塞主流程
        }
        Collections.sort(out, new Comparator<Sample>() {
            @Override public int compare(Sample a, Sample b) { return Long.compare(a.ts, b.ts); }
        });
        return out;
    }

    public static synchronized void save(Context c, List<Sample> list) {
        prune(list);
        JSONArray arr = new JSONArray();
        try {
            for (Sample s : list) {
                JSONObject o = new JSONObject();
                o.put("t", s.ts);
                o.put("v", round4(s.total));
                o.put("g", round4(s.granted));
                o.put("u", round4(s.toppedUp));
                o.put("c", s.currency == null ? "CNY" : s.currency);
                arr.put(o);
            }
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            File dst = file(c);
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
        } catch (Exception ignored) {
        }
    }

    /**
     * 追加一条采样。为避免重复轮询把历史撑爆，若与上一条间隔小于 20 秒则直接覆盖上一条。
     */
    public static synchronized List<Sample> append(Context c, Sample s) {
        List<Sample> list = load(c);
        if (!list.isEmpty()) {
            Sample last = list.get(list.size() - 1);
            if (s.ts - last.ts < 20_000L) {
                list.set(list.size() - 1, s);
                save(c, list);
                return list;
            }
        }
        list.add(s);
        save(c, list);
        return list;
    }

    private static void prune(List<Sample> list) {
        long cutoff = System.currentTimeMillis() - KEEP_MILLIS;
        while (!list.isEmpty() && list.get(0).ts < cutoff) list.remove(0);
        while (list.size() > MAX_SAMPLES) list.remove(0);
    }

    public static synchronized void clear(Context c) {
        File f = file(c);
        if (f.exists()) f.delete();
    }

    static String fileName() { return FILE; }

    private static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}
