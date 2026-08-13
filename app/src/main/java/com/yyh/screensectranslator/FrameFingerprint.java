package com.yyh.screensectranslator;

import android.graphics.Bitmap;

final class FrameFingerprint {
    private FrameFingerprint() {
    }

    static long differenceHash(Bitmap bitmap) {
        Bitmap tiny = Bitmap.createScaledBitmap(bitmap, 9, 8, true);
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = luminance(tiny.getPixel(x, y));
                int right = luminance(tiny.getPixel(x + 1, y));
                if (left > right) hash |= (1L << bit);
                bit++;
            }
        }
        if (tiny != bitmap) tiny.recycle();
        return hash;
    }

    static int distance(long first, long second) {
        return Long.bitCount(first ^ second);
    }

    private static int luminance(int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        return (red * 77 + green * 150 + blue * 29) >> 8;
    }
}
