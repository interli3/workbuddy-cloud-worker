package io.github.workbuddymonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/**
 * 轻量环形进度：底槽 + 渐变进度弧。
 * 只用 framework API，无任何第三方依赖；值为 0 时只画底槽，不会出现闪断。
 */
public class RingView extends View {

    private float value = 0f;          // 0 ~ 100
    private Paint track, fill;
    private final RectF oval = new RectF();
    private int stroke;
    // 深色玻璃主题（2026-09-28）：底槽改半透明白，弧线走薄荷青 -> 亮蓝
    private int cStart = 0xFF5FE3B4;
    private int cEnd = 0xFF7FC8FF;
    private static final int C_TRACK = 0x33FFFFFF;

    public RingView(Context c) { super(c); init(); }
    public RingView(Context c, AttributeSet a) { super(c, a); init(); }
    public RingView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        stroke = Math.round(5 * d);
        track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(C_TRACK);
        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(stroke);
        fill.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 0~100，越界自动裁剪 */
    public void setValue(float v) {
        float nv = v < 0 ? 0 : (v > 100 ? 100 : v);
        if (Math.abs(nv - value) < 0.4f) return;
        value = nv;
        invalidate();
    }

    public float getValue() { return value; }

    /** 进度弧渐变两端颜色 */
    public void setArcColors(int start, int end) {
        cStart = start;
        cEnd = end;
        fill.setShader(null);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float pad = stroke / 2f + 1f;
        oval.set(pad, pad, w - pad, h - pad);
        cv.drawArc(oval, 0f, 360f, false, track);
        if (value > 0.5f) {
            if (fill.getShader() == null) {
                try {
                    fill.setShader(new SweepGradient(w / 2f, h / 2f, cStart, cEnd));
                } catch (Throwable t) {
                    fill.setColor(cStart);
                }
            }
            cv.drawArc(oval, -90f, 360f * value / 100f, false, fill);
        }
    }
}
