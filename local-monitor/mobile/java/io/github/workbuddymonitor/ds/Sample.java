package io.github.workbuddymonitor.ds;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 一次 DeepSeek 余额采样。
 *
 * 说明：本包（io.github.workbuddymonitor.ds）是「DeepSeek 余额」子系统，
 * 与宿主 WorkBuddy 积分体系完全隔离：独立的偏好存储、独立的采样文件、
 * 独立的闹钟与通知渠道。两者金额互不引用、互不覆盖。
 */
public class Sample {
    public long ts;            // 采样时刻（epoch millis）
    public double total;       // 总可用余额
    public double granted;     // 未过期赠金
    public double toppedUp;    // 充值余额
    public String currency;    // CNY / USD

    public Sample() {}

    public Sample(long ts, double total, double granted, double toppedUp, String currency) {
        this.ts = ts;
        this.total = total;
        this.granted = granted;
        this.toppedUp = toppedUp;
        this.currency = currency;
    }

    public String timeText() {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ts));
    }
}
