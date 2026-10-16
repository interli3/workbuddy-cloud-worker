package io.github.workbuddymonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 近 7 天消耗迷你柱状图（Bento 卡里的 sparkline）。
 * 柱高按最大值归一化；峰值用主色高亮，其余用淡灰绿；无数据时静默不画。
 */
public class MiniChart extends View {

    private float[] values = new float[0];
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    // 深色玻璃主题（2026-09-28）：柱体走亮蓝，基线/空态走淡白
    private static final int C_IDLE = 0x33FFFFFF;
    private static final int C_HOT = 0xFF7FC8FF;
    private static final int C_BASE = 0x33FFFFFF;

    public MiniChart(Context c) { super(c); init(); }
    public MiniChart(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        pBase.setColor(C_BASE);
        pBase.setStyle(Paint.Style.STROKE);
        pBase.setStrokeWidth(dp(2));
        pBase.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 传入最近 7 天消耗（index 0 = 6 天前，index 6 = 今天） */
    public void setData(float[] v) {
        values = v == null ? new float[0] : v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float baseY = h - dp(1);
        cv.drawLine(dp(2), baseY, w - dp(2), baseY, pBase);

        int n = values.length;
        if (n == 0) return;

        float max = 0f;
        for (float v : values) max = Math.max(max, v);
        if (max <= 0f) {
            // 全程无消耗：画一条静默矮条，避免看起来像坏了
            pBar.setColor(C_IDLE);
            float bw = barW(n, w);
            float x = dp(2);
            for (int i = 0; i < n; i++) {
                rf.set(x, baseY - dp(3), x + bw, baseY);
                cv.drawRoundRect(rf, dp(2), dp(2), pBar);
                x += bw + gap(n, w);
            }
            return;
        }

        int hot = 0;
        for (int i = 1; i < n; i++) if (values[i] > values[hot]) hot = i;

        float bw = barW(n, w);
        float x = dp(2);
        for (int i = 0; i < n; i++) {
            float t = values[i] / max;
            float bh = Math.max(dp(3), t * (h - dp(6)));
            pBar.setColor(i == hot ? C_HOT : (t > 0.02f ? 0x8A5FA8FF : C_IDLE));
            rf.set(x, baseY - bh, x + bw, baseY);
            cv.drawRoundRect(rf, dp(2.5f), dp(2.5f), pBar);
            x += bw + gap(n, w);
        }
    }

    private float barW(int n, int w) {
        float gap = dp(4);
        float avail = w - dp(4) - gap * (n - 1);
        return Math.max(dp(3), avail / n);
    }

    private float gap(int n, int w) {
        return dp(4);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
