package com.yyh.screensectranslator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

final class TranslationOverlayView extends View {
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attributionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final List<ScreenTranslation> translations = new ArrayList<>();
    private final float density;
    private String attribution = "由 Google 翻译提供支持";

    TranslationOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        cardPaint.setColor(Color.argb(238, 13, 20, 31));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(Color.argb(220, 77, 225, 193));
        attributionPaint.setColor(Color.argb(232, 13, 20, 31));
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
    }

    void setTranslations(List<ScreenTranslation> items) {
        setTranslations(items, "由 Google 翻译提供支持");
    }

    void setTranslations(List<ScreenTranslation> items, String attribution) {
        translations.clear();
        translations.addAll(items);
        this.attribution = attribution == null ? "" : attribution.trim();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;

        float outerMargin = dp(3);
        float paddingX = dp(7);
        float paddingY = dp(5);

        for (ScreenTranslation item : translations) {
            float left = item.x / 1000f * viewWidth;
            float top = item.y / 1000f * viewHeight;
            float sourceWidth = item.width / 1000f * viewWidth;
            float sourceHeight = item.height / 1000f * viewHeight;

            left = Math.max(outerMargin, Math.min(left, viewWidth - dp(70)));
            top = Math.max(outerMargin, Math.min(top, viewHeight - dp(28)));

            float textSp = clamp(sourceHeight / density * 0.54f, 11f, 19f);
            textPaint.setTextSize(textSp * density);

            float estimated = textPaint.measureText(item.translated) + paddingX * 2;
            float preferred = Math.max(sourceWidth, Math.min(estimated, dp(290)));
            float cardWidth = clamp(preferred, dp(72), viewWidth - left - outerMargin);
            int layoutWidth = Math.max(1, Math.round(cardWidth - paddingX * 2));

            StaticLayout layout = StaticLayout.Builder
                    .obtain(item.translated, 0, item.translated.length(), textPaint, layoutWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.04f)
                    .build();

            float cardHeight = Math.max(sourceHeight, layout.getHeight() + paddingY * 2);
            if (top + cardHeight > viewHeight - outerMargin) {
                top = Math.max(outerMargin, viewHeight - outerMargin - cardHeight);
            }
            RectF card = new RectF(left, top, left + cardWidth, top + cardHeight);
            float radius = dp(6);
            canvas.drawRoundRect(card, radius, radius, cardPaint);
            canvas.drawRoundRect(card, radius, radius, borderPaint);

            canvas.save();
            canvas.clipRect(card);
            canvas.translate(left + paddingX, top + Math.max(paddingY,
                    (cardHeight - layout.getHeight()) / 2f));
            layout.draw(canvas);
            canvas.restore();
        }

        drawAttribution(canvas, viewWidth, viewHeight);
    }

    private void drawAttribution(Canvas canvas, int viewWidth, int viewHeight) {
        if (translations.isEmpty() || attribution.isEmpty()) return;

        float textSize = dp(10);
        textPaint.setTextSize(textSize);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
        float paddingX = dp(7);
        float paddingY = dp(4);
        float margin = dp(6);
        float width = textPaint.measureText(attribution) + paddingX * 2;
        float height = textPaint.getFontMetrics().bottom - textPaint.getFontMetrics().top + paddingY * 2;
        float left = Math.max(margin, viewWidth - margin - width);
        float top = Math.max(margin, viewHeight - dp(36) - height);
        RectF badge = new RectF(left, top, left + width, top + height);
        canvas.drawRoundRect(badge, dp(5), dp(5), attributionPaint);
        canvas.drawText(attribution, left + paddingX, top + paddingY - textPaint.getFontMetrics().top,
                textPaint);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
    }

    private float dp(float value) {
        return value * density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
