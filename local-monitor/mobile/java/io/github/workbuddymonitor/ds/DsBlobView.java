package io.github.workbuddymonitor.ds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 背景装饰光斑：三团柔和径向渐变，营造"冰川玻璃"的冷调氛围。
 * 不用 RenderEffect 模糊 —— 径向渐变自身的柔化在全部 API 上都够看，且低端机零开销。
 */
public class DsBlobView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DsBlobView(Context c) { super(c); }

    public DsBlobView(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float m = Math.max(w, h);
        blob(cv, w * 0.10f, h * 0.05f, m * 0.52f, 0x334D6BFE);
        blob(cv, w * 0.95f, h * 0.20f, m * 0.50f, 0x2E7C5CFF);
        blob(cv, w * 0.28f, h * 0.80f, m * 0.58f, 0x261FB6CF);
    }

    private void blob(Canvas cv, float cx, float cy, float r, int color) {
        try {
            int edge = color & 0x00FFFFFF;   // 同色但全透明，渐变到无
            paint.setShader(new RadialGradient(cx, cy, r, color, edge, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r, paint);
        } catch (Throwable ignored) {
        } finally {
            paint.setShader(null);
        }
    }
}
