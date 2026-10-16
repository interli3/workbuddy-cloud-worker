package io.github.workbuddymonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/** Soft decorative light field; GPU Gaussian blur on Android 12+. */
public class BlurBlobView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BlurBlobView(Context c, AttributeSet a) {
        super(c, a);
        if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP));
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        glow(canvas, w * .86f, h * .05f, w * .48f, 0x665B8FF9);
        glow(canvas, w * .05f, h * .18f, w * .42f, 0x5527C7A7);
        glow(canvas, w * .76f, h * .52f, w * .38f, 0x408B7CF6);
    }

    private void glow(Canvas canvas, float x, float y, float r, int color) {
        paint.setShader(new RadialGradient(x, y, r,
                new int[]{color, Color.TRANSPARENT}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r, paint);
        paint.setShader(null);
    }
}
