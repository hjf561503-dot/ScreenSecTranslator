package com.yyh.screensectranslator;

import org.json.JSONObject;

final class ScreenTranslation {
    final String source;
    final String translated;
    final int x;
    final int y;
    final int width;
    final int height;

    private ScreenTranslation(String source, String translated,
                              int x, int y, int width, int height) {
        this.source = source;
        this.translated = translated;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    static ScreenTranslation of(String source, String translated,
                                int x, int y, int width, int height) {
        if (translated == null || translated.trim().isEmpty()) return null;
        int safeX = clamp(x, 0, 999);
        int safeY = clamp(y, 0, 999);
        return new ScreenTranslation(
                source == null ? "" : source.trim(),
                translated.trim(),
                safeX,
                safeY,
                clamp(width, 1, 1000 - safeX),
                clamp(height, 1, 1000 - safeY));
    }

    static ScreenTranslation fromJson(JSONObject object) {
        if (object == null) return null;
        String source = object.optString("source", "").trim();
        String translated = object.optString("translated", "").trim();
        if (translated.isEmpty()) return null;
        int x = clamp(object.optInt("x", 0), 0, 999);
        int y = clamp(object.optInt("y", 0), 0, 999);
        int width = clamp(object.optInt("width", 1), 1, 1000 - x);
        int height = clamp(object.optInt("height", 1), 1, 1000 - y);
        return new ScreenTranslation(source, translated, x, y, width, height);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
