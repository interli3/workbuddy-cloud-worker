package io.github.workbuddymonitor.ds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 近 7 天消耗柱状图。
 * 无数据时画一排静默矮条而不是空白 —— 空白会被误读成"坏了"。
 */
public class DsBarChartView extends View {

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private double[] values = new double[7];
    private String[] labels = new String[]{"", "", "", "", "", "", ""};
    private String unit = "¥";
    private int todayIndex = 6;

    private int cAccentTop = 0xFFB9C6FF;
    private int cAccentBottom = 0xFF4D6BFE;
    private int cDimTop = 0xFFD3DCF7;
    private int cDimBottom = 0xFF9FB2E8;
    private int cGrid = 0x2E7C8AA6;
    private int cLabel = 0xFF8C9AB0;
    private int cLabelActive = 0xFF4D6BFE;
    private int cValue = 0xFF3A54D6;

    public DsBarChartView(Context c) { super(c); init(); }

    public DsBarChartView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        line.setStrokeWidth(dp(1f));
        line.setColor(cGrid);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(double[] v, String[] lab) {
        if (v != null && v.length == 7) {
            values = v;
        }
        if (lab != null && lab.length == 7) {
            labels = lab;
        }
        invalidate();
    }

    public void setUnit(String u) {
        if (u != null) unit = u;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float labelH = dp(16f);
        float valueH = dp(14f);
        float baseY = h - labelH;
        float topY = valueH;

        // 基线
        cv.drawLine(0, baseY, w, baseY, line);

        double max = 0;
        for (double v : values) max = Math.max(max, v);

        float slot = w / 7f;
        float bw = Math.min(slot * 0.46f, dp(26f));

        if (max <= 0.000001d) {
            bar.setColor(0x3394A3B8);
            for (int i = 0; i < 7; i++) {
                float cx = i * slot + slot / 2f;
                cv.drawRoundRect(new RectF(cx - bw / 2f, baseY - dp(3f), cx + bw / 2f, baseY),
                        dp(1.5f), dp(1.5f), bar);
            }
        } else {
            float usable = baseY - topY;
            for (int i = 0; i < 7; i++) {
                float cx = i * slot + slot / 2f;
                boolean active = (i == todayIndex);
                float ratio = (float) (values[i] / max);
                float bh = values[i] <= 0 ? dp(3f) : Math.max(dp(4f), ratio * usable);
                float top = baseY - bh;

                try {
                    bar.setShader(new LinearGradient(0, top, 0, baseY,
                            active ? cAccentTop : cDimTop,
                            active ? cAccentBottom : cDimBottom,
                            Shader.TileMode.CLAMP));
                } catch (Throwable t) {
                    bar.setShader(null);
                    bar.setColor(active ? cAccentBottom : cDimBottom);
                }
                cv.drawRoundRect(new RectF(cx - bw / 2f, top, cx + bw / 2f, baseY), bw / 2f, bw / 2f, bar);
                bar.setShader(null);
            }
            // 峰值标注
            int peak = 0;
            for (int i = 1; i < 7; i++) if (values[i] > values[peak]) peak = i;
            float pcx = peak * slot + slot / 2f;
            float pratio = (float) (values[peak] / max);
            float ptop = baseY - Math.max(dp(4f), pratio * usable);
            text.setTextSize(dp(10f));
            text.setColor(cValue);
            text.setFakeBoldText(true);
            cv.drawText(unit + fmt(values[peak]), clamp(pcx, dp(20f), w - dp(20f)), Math.max(dp(10f), ptop - dp(3f)), text);
            text.setFakeBoldText(false);
        }

        // 横轴标签
        text.setTextSize(dp(10f));
        for (int i = 0; i < 7 && i < labels.length; i++) {
            float cx = i * slot + slot / 2f;
            boolean active = (i == todayIndex);
            text.setColor(active ? cLabelActive : cLabel);
            text.setFakeBoldText(active);
            cv.drawText(labels[i], cx, h - dp(3f), text);
            text.setFakeBoldText(false);
        }
    }

    private float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private String fmt(double v) {
        if (v >= 100) return String.format(java.util.Locale.CHINA, "%.0f", v);
        if (v >= 10) return String.format(java.util.Locale.CHINA, "%.1f", v);
        return String.format(java.util.Locale.CHINA, "%.2f", v);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
