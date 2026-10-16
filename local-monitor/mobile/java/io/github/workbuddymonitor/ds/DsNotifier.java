package io.github.workbuddymonitor.ds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import io.github.workbuddymonitor.MainActivity;
import io.github.workbuddymonitor.R;

/**
 * DeepSeek 余额预警通知。跌破阈值提醒一次，回升后重新武装，避免反复骚扰。
 *
 * 隔离点：渠道 id 用 "ds_balance_warn"、通知 id 用 2101/2102（宿主 WorkBuddy 不用通知），
 * 因此与宿主的任何提示互不覆盖。
 *
 * 注意：Android 13+ 的 POST_NOTIFICATIONS 是运行时权限，只声明不申请会被系统静默丢弃，
 * 且界面上完全看不出来 —— 所以设置页必须显式申请，并提供「发送测试通知」按钮验证。
 */
public final class DsNotifier {

    private static final String CH_WARN = "ds_balance_warn";
    private static final int ID_WARN = 2101;
    private static final int ID_TEST = 2102;

    private DsNotifier() {}

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CH_WARN) != null) return;
        NotificationChannel ch = new NotificationChannel(CH_WARN, "DeepSeek 余额预警", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("DeepSeek 余额低于设定阈值时提醒");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    public static void maybeNotify(Context c, DsPrefs p, Stats st) {
        if (!p.isNotifyEnabled() || !p.isWarnEnabled()) return;
        if (!st.hasData) return;
        double th = p.getWarnThreshold();
        boolean below = st.current < th;
        if (below && p.isWarnArmed()) {
            p.setWarnArmed(false);
            notify(c, st, th);
        } else if (!below && !p.isWarnArmed()) {
            p.setWarnArmed(true);
        }
    }

    /** 用户主动点「发送测试通知」时调用：无视阈值，直接发一条，用来验证权限与渠道是否真的通了。 */
    public static void sendTest(Context c) {
        ensureChannel(c);
        Stats st = Stats.compute(c, new DsPrefs(c));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String body = st.hasData
                ? "通知渠道工作正常。当前余额 " + Stats.money(st.currency, st.current)
                  + "，今日已消耗 " + Stats.money(st.currency, st.todaySpend) + "。"
                : "通知渠道工作正常。等第一次成功采样后，这里会显示真实余额。";

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CH_WARN)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ds_ic_stat_balance)
                .setContentTitle("DeepSeek 余额 · 测试通知")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(mainPi(c, 3002))
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFF4D6BFE);
        nm.notify(ID_TEST, b.build());
    }

    private static void notify(Context c, Stats st, double th) {
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String title = "DeepSeek 余额偏低 · " + Stats.money(st.currency, st.current);
        StringBuilder sb = new StringBuilder();
        sb.append("已低于预警线 ").append(Stats.money(st.currency, th));
        if (st.daysLeft > 0) sb.append("，按近期速度约可用 ").append(st.daysLeft).append(" 天");
        sb.append("。今日已消耗 ").append(Stats.money(st.currency, st.todaySpend));

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CH_WARN)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ds_ic_stat_balance)
                .setContentTitle(title)
                .setContentText(sb.toString())
                .setStyle(new Notification.BigTextStyle().bigText(sb.toString()))
                .setContentIntent(mainPi(c, 3001))
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFF4D6BFE);
        nm.notify(ID_WARN, b.build());
    }

    /** 点通知直接进 App 的 DeepSeek 页。 */
    private static PendingIntent mainPi(Context c, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_DEEPSEEK);
        // 换 requestCode 才能让「测试通知」与「预警通知」各自带自己的 extra
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, req, i, flags);
    }
}
