package io.github.workbuddymonitor;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.workbuddymonitor.ds.DsNotifier;
import io.github.workbuddymonitor.ds.DsPrefs;
import io.github.workbuddymonitor.ds.DsRefreshTask;
import io.github.workbuddymonitor.ds.DsScheduler;
import io.github.workbuddymonitor.ds.DsStore;
import io.github.workbuddymonitor.ds.DsWidgets;
import io.github.workbuddymonitor.ds.Stats;

/**
 * 设置页。
 *
 * 上半部分是 WorkBuddy 积分的数据源配置（伴侣服务地址），
 * 下半部分是 DeepSeek 余额的独立配置（API Key / 采样间隔 / 预警 / 通知）。
 * 两套配置存在不同的 SharedPreferences 里，互不覆盖。
 */
public class SettingsActivity extends Activity {

    private EditText etHost, etLan, etToken;
    private Spinner spInterval;
    private TextView tvResult;

    // ---- DeepSeek ----
    private EditText dsEtKey, dsEtWarn;
    private TextView dsTvKeyState, dsTvKeyResult, dsTvWarnSub, dsTvNotifyState, dsTvDataInfo;
    private TextView[] dsSeg;
    private Switch dsSwWarn, dsSwNotify;

    private static final int REQ_NOTIFY = 41;
    private static final int[] INTERVAL_MIN = {5, 10, 15, 30, 60};

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.migrate(this);
        setContentView(R.layout.activity_settings);

        // ==================== WorkBuddy 积分配置 ====================
        etHost = findViewById(R.id.etHost);
        etLan = findViewById(R.id.etLan);
        etToken = findViewById(R.id.etToken);
        spInterval = findViewById(R.id.spInterval);
        tvResult = findViewById(R.id.tvResult);

        etHost.setText(Store.host(this));
        etLan.setText(Store.lanHost(this));
        etToken.setText(Store.token(this));

        String[] items = {"15 秒", "30 秒", "1 分钟", "3 分钟", "10 分钟"};
        // 用自带布局而不是系统 simple_spinner_*：
        // 系统布局在深色主题下会被压成 0 高、文字也不可见（实测 Spinner 高度仅 16dp）
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, R.layout.item_spinner, items);
        spInterval.setAdapter(ad);
        spInterval.setSelection(Store.intervalIndex(this));

        Button btnTest = findViewById(R.id.btnTest);
        Button btnSave = findViewById(R.id.btnSave);
        btnTest.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tvResult.setText("测试中…");
                Store.setHost(SettingsActivity.this, etHost.getText().toString().trim());
                Store.setLanHost(SettingsActivity.this, etLan.getText().toString().trim());
                Store.setToken(SettingsActivity.this, etToken.getText().toString().trim());
                Fetcher.fetch(SettingsActivity.this, new Fetcher.Cb() {
                    public void onResult(final Snapshot s, final String err) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (s != null && err == null) {
                                    tvResult.setText("连接成功 · 总余额 " + MainActivity.fmtNum(s.totalBalance)
                                            + " · " + s.accountCount + " 个账号 · 已签 " + s.signedCount);
                                } else {
                                    tvResult.setText("连接失败：" + err);
                                }
                            }
                        });
                    }
                });
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Store.setHost(SettingsActivity.this, etHost.getText().toString().trim());
                Store.setLanHost(SettingsActivity.this, etLan.getText().toString().trim());
                Store.setToken(SettingsActivity.this, etToken.getText().toString().trim());
                Store.setIntervalIndex(SettingsActivity.this, spInterval.getSelectedItemPosition());
                Scheduler.schedule(SettingsActivity.this);
                WbWidget.pushAll(SettingsActivity.this);
                tvResult.setText("已保存，刷新间隔 " + (Store.interval(SettingsActivity.this) / 1000) + " 秒");
            }
        });

        // ==================== DeepSeek 余额配置 ====================
        initDeepSeek();
    }

    // ============================================================
    //                     DeepSeek 分组
    // ============================================================

    private void initDeepSeek() {
        final DsPrefs p = new DsPrefs(this);

        dsEtKey = findViewById(R.id.dsEtKey);
        dsTvKeyState = findViewById(R.id.dsTvKeyState);
        dsTvKeyResult = findViewById(R.id.dsTvKeyResult);
        dsEtWarn = findViewById(R.id.dsEtWarn);
        dsTvWarnSub = findViewById(R.id.dsTvWarnSub);
        dsSwWarn = findViewById(R.id.dsSwWarn);
        dsSwNotify = findViewById(R.id.dsSwNotify);
        dsTvNotifyState = findViewById(R.id.dsTvNotifyState);
        dsTvDataInfo = findViewById(R.id.dsTvDataInfo);
        dsSeg = new TextView[]{findViewById(R.id.dsIv5), findViewById(R.id.dsIv10),
                findViewById(R.id.dsIv15), findViewById(R.id.dsIv30), findViewById(R.id.dsIv60)};

        if (p.hasKey()) dsEtKey.setText(p.getKey());
        dsEtWarn.setText(trimNum(p.getWarnThreshold()));
        dsSwWarn.setChecked(p.isWarnEnabled());
        dsSwNotify.setChecked(p.isNotifyEnabled());
        refreshKeyState();
        refreshSegments();
        refreshNotifyState();
        refreshDataInfo();

        findViewById(R.id.dsBtnSaveKey).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String k = dsEtKey.getText().toString().trim();
                p.setKey(k);
                refreshKeyState();
                if (k.isEmpty()) {
                    dsTvKeyResult.setText("已清空 API Key，采样已停止。");
                    return;
                }
                dsTvKeyResult.setText("校验中…");
                DsRefreshTask.run(getApplicationContext(), new DsRefreshTask.Callback() {
                    public void done(final boolean ok, final String error, final Stats st) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (ok && st.hasData) {
                                    dsTvKeyResult.setText("校验通过 · 当前余额 "
                                            + Stats.money(st.currency, st.current)
                                            + "（赠金 " + Stats.money(st.currency, st.granted)
                                            + " / 充值 " + Stats.money(st.currency, st.toppedUp) + "）");
                                } else {
                                    dsTvKeyResult.setText("校验失败：" + (error == null ? "未知错误" : error));
                                }
                                refreshKeyState();
                                refreshDataInfo();
                                WbWidget.renderAllFromCache(SettingsActivity.this);
                            }
                        });
                    }
                });
            }
        });

        findViewById(R.id.dsBtnClearKey).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                p.setKey("");
                dsEtKey.setText("");
                dsTvKeyResult.setText("已清空 API Key。");
                refreshKeyState();
            }
        });

        for (int i = 0; i < dsSeg.length; i++) {
            final int idx = i;
            dsSeg[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    p.setIntervalMin(INTERVAL_MIN[idx]);
                    DsScheduler.schedule(SettingsActivity.this, INTERVAL_MIN[idx]);
                    refreshSegments();
                    Toast.makeText(SettingsActivity.this,
                            "DeepSeek 采样间隔已设为 " + segLabel(idx), Toast.LENGTH_SHORT).show();
                }
            });
        }

        dsSwWarn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                p.setWarnEnabled(dsSwWarn.isChecked());
                refreshWarnSub();
            }
        });

        findViewById(R.id.dsBtnSaveWarn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                double th = 10d;
                try { th = Double.parseDouble(dsEtWarn.getText().toString().trim()); }
                catch (Exception ignored) { }
                if (th <= 0) th = 10d;
                p.setWarnThreshold(th);
                dsEtWarn.setText(trimNum(th));
                refreshWarnSub();
                Toast.makeText(SettingsActivity.this, "预警线已设为 ¥" + trimNum(th), Toast.LENGTH_SHORT).show();
            }
        });

        dsSwNotify.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = dsSwNotify.isChecked();
                p.setNotifyEnabled(on);
                if (on) requestNotifyPermissionIfNeeded();
                refreshNotifyState();
            }
        });

        findViewById(R.id.dsBtnTestNotify).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!hasNotifyPermission()) {
                    requestNotifyPermissionIfNeeded();
                    return;
                }
                DsNotifier.sendTest(SettingsActivity.this);
                refreshNotifyState();
                Toast.makeText(SettingsActivity.this, "已发送测试通知", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.dsBtnClearHistory).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                DsStore.clear(SettingsActivity.this);
                Stats st = DsRefreshTask.cached(SettingsActivity.this);
                WbWidget.renderAllFromCache(SettingsActivity.this);
                refreshDataInfo();
                Toast.makeText(SettingsActivity.this, "已清空 DeepSeek 采样历史", Toast.LENGTH_SHORT).show();
            }
        });

        refreshWarnSub();
    }

    private void refreshKeyState() {
        boolean has = new DsPrefs(this).hasKey();
        dsTvKeyState.setText(has ? "已配置" : "未配置");
        dsTvKeyState.setTextColor(getResources().getColor(has ? R.color.accent_deep : R.color.warn));
    }

    private void refreshSegments() {
        int cur = new DsPrefs(this).getIntervalMin();
        int sel = 2;
        for (int i = 0; i < INTERVAL_MIN.length; i++) if (INTERVAL_MIN[i] == cur) sel = i;
        for (int i = 0; i < dsSeg.length; i++) {
            boolean on = i == sel;
            dsSeg[i].setBackgroundResource(on ? R.drawable.bg_segment_active : R.drawable.bg_segment_idle);
            dsSeg[i].setTextColor(getResources().getColor(on ? R.color.accent_deep : R.color.text_dim));
            dsSeg[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void refreshWarnSub() {
        DsPrefs p = new DsPrefs(this);
        boolean on = p.isWarnEnabled();
        dsTvWarnSub.setText(on
                ? "低于 ¥" + trimNum(p.getWarnThreshold()) + " 时发通知，回升后重新武装"
                : "已关闭 — 余额再低也不会提醒");
    }

    private void refreshDataInfo() {
        int n = DsRefreshTask.history(this).size();
        Stats st = DsRefreshTask.cached(this);
        StringBuilder sb = new StringBuilder();
        sb.append("本机已存 ").append(n).append(" 条采样");
        if (st.hasData) {
            long h = st.historySpanMs / 3_600_000L;
            sb.append("，跨度约 ").append(h < 1 ? (st.historySpanMs / 60_000L) + " 分钟" : h + " 小时");
            sb.append("\n最近一次 ").append(DsWidgets.ageText(st.lastTs));
        }
        sb.append("\n采样与 API Key 都只存在本机，卸载即抹除。");
        dsTvDataInfo.setText(sb.toString());
    }

    // ==================== 通知权限（Android 13+ 必须运行时申请） ====================

    private boolean hasNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotifyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (hasNotifyPermission()) return;
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    private void refreshNotifyState() {
        boolean granted = hasNotifyPermission();
        if (Build.VERSION.SDK_INT < 33) {
            dsTvNotifyState.setText("当前系统版本无需单独授权，通知开关以右侧为准。");
        } else if (granted) {
            dsTvNotifyState.setText("系统通知权限：已授予 ✅");
        } else {
            dsTvNotifyState.setText("系统通知权限：未授予 ⚠ 只声明不申请会被系统静默丢弃，"
                    + "界面看不出任何异常。点上面的按钮授权，或到系统设置里手动打开。");
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_NOTIFY) {
            refreshNotifyState();
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, ok ? "通知权限已授予" : "通知权限被拒绝，测试通知可能发不出来",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 工具 ====================

    private static String segLabel(int i) {
        int m = INTERVAL_MIN[Math.max(0, Math.min(INTERVAL_MIN.length - 1, i))];
        return m >= 60 ? (m / 60) + " 小时" : m + " 分钟";
    }

    private static String trimNum(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    static PendingIntent pi(Context c) {
        Intent i = new Intent(c, TickReceiver.class);
        i.setAction("io.github.workbuddymonitor.TICK");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, 1001, i, flags);
    }
}
