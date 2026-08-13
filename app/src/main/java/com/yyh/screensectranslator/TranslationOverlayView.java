package com.yyh.screensectranslator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

final class TranslationOverlayView extends View {
    static final float MASK_SCALE = 1.10f;
    private static final float MIN_CONTRAST = 4.5f;

    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attributionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(
            Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final List<ScreenTranslation> translations = new ArrayList<>();
    private final float density;
    private String attribution = "由 Google 翻译提供支持";
    private Bitmap screenshot;

    TranslationOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        attributionPaint.setColor(Color.argb(220, 13, 20, 31));
        textPaint.setTypeface(android.graphics.Typeface.create(
                "sans", android.graphics.Typeface.NORMAL));
    }

    void setTranslations(List<ScreenTranslation> items, String attribution, Bitmap screenshot) {
        translations.clear();
        translations.addAll(items);
        this.attribution = attribution == null ? "" : attribution.trim();
        this.screenshot = screenshot;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        Bitmap frame = screenshot;
        if (viewWidth <= 0 || viewHeight <= 0 || frame == null || frame.isRecycled()) return;

        for (ScreenTranslation item : translations) {
            RectF original = normalizedRect(item, viewWidth, viewHeight);
            RectF mask = expandedByTenPercent(original, viewWidth, viewHeight);
            if (mask.width() < 1f || mask.height() < 1f) continue;

            Rect source = bitmapRect(mask, viewWidth, viewHeight, frame);
            int dominant = dominantColor(frame, source);
            drawBlurMask(canvas, frame, source, mask, dominant);
            drawFittedTranslation(canvas, item.translated, original, mask, dominant);
        }
        drawAttribution(canvas, viewWidth, viewHeight);
    }

    private void drawBlurMask(Canvas canvas, Bitmap frame, Rect source, RectF mask, int dominant) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float radius = Math.max(dp(3), Math.min(mask.width(), mask.height()) * 0.16f);
            blurPaint.setRenderEffect(RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP));
        }
        canvas.save();
        canvas.clipRect(mask);
        canvas.drawBitmap(frame, source, mask, blurPaint);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurPaint.setRenderEffect(null);
        tintPaint.setColor(Color.argb(96, Color.red(dominant),
                Color.green(dominant), Color.blue(dominant)));
        canvas.drawRect(mask, tintPaint);
        canvas.restore();
    }

    private void drawFittedTranslation(Canvas canvas, String translated, RectF original,
                                       RectF mask, int dominant) {
        if (translated == null || translated.isEmpty()) return;
        float insetX = Math.max(dp(1), mask.width() * 0.025f);
        float insetY = Math.max(dp(1), mask.height() * 0.025f);
        int layoutWidth = Math.max(1, Math.round(mask.width() - insetX * 2));
        float allowedHeight = Math.max(1f, mask.height() - insetY * 2);
        float sizePx = clamp(original.height() * 0.82f, dp(7), dp(40));
        StaticLayout layout;
        do {
            textPaint.setTextSize(sizePx);
            layout = buildLayout(translated, layoutWidth);
            if (layout.getHeight() <= allowedHeight || sizePx <= dp(6)) break;
            sizePx -= dp(0.75f);
        } while (true);

        textPaint.setColor(oppositeColor(dominant));
        canvas.save();
        canvas.clipRect(mask);
        canvas.translate(mask.left + insetX,
                mask.top + Math.max(insetY, (mask.height() - layout.getHeight()) / 2f));
        layout.draw(canvas);
        canvas.restore();
    }

    private StaticLayout buildLayout(String text, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build();
    }

    private static RectF normalizedRect(ScreenTranslation item, int width, int height) {
        float left = item.x / 1000f * width;
        float top = item.y / 1000f * height;
        float right = (item.x + item.width) / 1000f * width;
        float bottom = (item.y + item.height) / 1000f * height;
        return new RectF(left, top, right, bottom);
    }

    private static RectF expandedByTenPercent(RectF original, int width, int height) {
        float extraX = original.width() * (MASK_SCALE - 1f) / 2f;
        float extraY = original.height() * (MASK_SCALE - 1f) / 2f;
        return new RectF(
                clamp(original.left - extraX, 0f, width),
                clamp(original.top - extraY, 0f, height),
                clamp(original.right + extraX, 0f, width),
                clamp(original.bottom + extraY, 0f, height));
    }

    private static Rect bitmapRect(RectF viewRect, int viewWidth, int viewHeight, Bitmap bitmap) {
        int left = clamp(Math.round(viewRect.left / Math.max(1, viewWidth) * bitmap.getWidth()),
                0, Math.max(0, bitmap.getWidth() - 1));
        int top = clamp(Math.round(viewRect.top / Math.max(1, viewHeight) * bitmap.getHeight()),
                0, Math.max(0, bitmap.getHeight() - 1));
        int right = clamp(Math.round(viewRect.right / Math.max(1, viewWidth) * bitmap.getWidth()),
                left + 1, bitmap.getWidth());
        int bottom = clamp(Math.round(viewRect.bottom / Math.max(1, viewHeight) * bitmap.getHeight()),
                top + 1, bitmap.getHeight());
        return new Rect(left, top, right, bottom);
    }

    private static int dominantColor(Bitmap bitmap, Rect area) {
        int[] histogram = new int[4096];
        int stepX = Math.max(1, area.width() / 18);
        int stepY = Math.max(1, area.height() / 18);
        int winningBin = 0;
        int winningCount = -1;
        for (int y = area.top; y < area.bottom; y += stepY) {
            for (int x = area.left; x < area.right; x += stepX) {
                int color = bitmap.getPixel(x, y);
                if (Color.alpha(color) < 128) continue;
                int bin = (Color.red(color) >> 4) << 8
                        | (Color.green(color) >> 4) << 4
                        | (Color.blue(color) >> 4);
                int count = ++histogram[bin];
                if (count > winningCount) {
                    winningCount = count;
                    winningBin = bin;
                }
            }
        }
        int red = ((winningBin >> 8) & 0x0f) * 17;
        int green = ((winningBin >> 4) & 0x0f) * 17;
        int blue = (winningBin & 0x0f) * 17;
        return Color.rgb(red, green, blue);
    }

    private static int oppositeColor(int background) {
        int complement = Color.rgb(255 - Color.red(background),
                255 - Color.green(background), 255 - Color.blue(background));
        if (contrastRatio(complement, background) >= MIN_CONTRAST) return complement;
        return contrastRatio(Color.BLACK, background) >= contrastRatio(Color.WHITE, background)
                ? Color.BLACK : Color.WHITE;
    }

    private static float contrastRatio(int foreground, int background) {
        float light = Math.max(luminance(foreground), luminance(background));
        float dark = Math.min(luminance(foreground), luminance(background));
        return (light + 0.05f) / (dark + 0.05f);
    }

    private static float luminance(int color) {
        float red = linear(Color.red(color) / 255f);
        float green = linear(Color.green(color) / 255f);
        float blue = linear(Color.blue(color) / 255f);
        return 0.2126f * red + 0.7152f * green + 0.0722f * blue;
    }

    private static float linear(float value) {
        return value <= 0.03928f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4);
    }

    private void drawAttribution(Canvas canvas, int viewWidth, int viewHeight) {
        if (translations.isEmpty() || attribution.isEmpty()) return;
        textPaint.setTextSize(dp(10));
        textPaint.setColor(Color.WHITE);
        float paddingX = dp(7);
        float paddingY = dp(4);
        float margin = dp(6);
        float width = textPaint.measureText(attribution) + paddingX * 2;
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float height = metrics.bottom - metrics.top + paddingY * 2;
        float left = Math.max(margin, viewWidth - margin - width);
        float top = Math.max(margin, viewHeight - dp(36) - height);
        RectF badge = new RectF(left, top, left + width, top + height);
        canvas.drawRoundRect(badge, dp(5), dp(5), attributionPaint);
        canvas.drawText(attribution, left + paddingX, top + paddingY - metrics.top, textPaint);
    }

    private float dp(float value) {
        return value * density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
