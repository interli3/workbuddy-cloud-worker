package io.github.workbuddymonitor.ds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 余额走势曲线：平滑折线 + 渐变面积 + 末点高亮。 */
public class DsSparkView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();

    private float[] data = new float[0];

    private int cStroke = 0xFF4D6BFE;
    private int cFillTop = 0x4D6B87FF;
    private int cFillBottom = 0x004D6BFE;

    public DsSparkView(Context c) { super(c); init(); }

    public DsSparkView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(cStroke);
        fill.setStyle(Paint.Style.FILL);
        grid.setStrokeWidth(dp(1f));
        grid.setColor(0x1F7C8AA6);
    }

    public void setData(float[] v) {
        data = (v == null ? new float[0] : v);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padT = dp(8f);
        float padB = dp(6f);

        // 淡淡的横向参考线（顶=区间最高，底=区间最低）
        grid.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(3f), dp(4f)}, 0));
        grid.setColor(0x2E7C8AA6);
        cv.drawLine(0, padT, w, padT, grid);
        cv.drawLine(0, h - padB, w, h - padB, grid);
        grid.setPathEffect(null);

        int n = data.length;
        if (n == 0) return;

        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float v : data) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (hi - lo < 0.0001f) {          // 余额没变：画一条居中的直线，避免放大采样噪声
            float mid = (hi + lo) / 2f;
            hi = mid + 0.5f;
            lo = mid - 0.5f;
        }
        float span = hi - lo;
        float usable = h - padT - padB;

        float stepX = n > 1 ? (float) w / (n - 1) : 0;

        path.reset();
        area.reset();
        float firstX = 0, firstY = y(0, lo, span, usable, padT);
        path.moveTo(firstX, firstY);
        area.moveTo(firstX, h);
        area.lineTo(firstX, firstY);

        if (n == 1) {
            path.lineTo(w, firstY);
            area.lineTo(w, firstY);
        } else {
            for (int i = 1; i < n; i++) {
                float x0 = (i - 1) * stepX;
                float y0 = y(i - 1, lo, span, usable, padT);
                float x1 = i * stepX;
                float y1 = y(i, lo, span, usable, padT);
                float mx = (x0 + x1) / 2f;
                path.cubicTo(mx, y0, mx, y1, x1, y1);
                area.cubicTo(mx, y0, mx, y1, x1, y1);
            }
        }
        area.lineTo(w, h);
        area.close();

        try {
            fill.setShader(new LinearGradient(0, padT, 0, h, cFillTop, cFillBottom, Shader.TileMode.CLAMP));
        } catch (Throwable t) {
            fill.setShader(null);
            fill.setColor(0x224D6BFE);
        }
        cv.drawPath(area, fill);
        fill.setShader(null);
        cv.drawPath(path, stroke);

        // 末点
        float lx = (n - 1) * stepX;
        float ly = y(n - 1, lo, span, usable, padT);
        dot.setColor(0xFFFFFFFF);
        cv.drawCircle(lx, ly, dp(4f), dot);
        dot.setColor(cStroke);
        cv.drawCircle(lx, ly, dp(2.4f), dot);
    }

    /** 注意：第三个参数是「区间跨度 span = hi - lo」，不是 hi。传错会把曲线压成一条平线。 */
    private float y(int i, float lo, float span, float usable, float padT) {
        float ratio = (data[i] - lo) / span;
        return padT + (1f - ratio) * usable;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
