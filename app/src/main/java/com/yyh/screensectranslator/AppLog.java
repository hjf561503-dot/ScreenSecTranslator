package com.yyh.screensectranslator;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class AppLog {
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 2L * 1024L * 1024L;
    private static final String ACTIVE_NAME = "screen-sec-translator.log";
    private static final String PREVIOUS_NAME = "screen-sec-translator.previous.log";

    private AppLog() {
    }

    static void appStarted(Context context) {
        info(context, "APP", "START",
                "version=" + appVersion(context) + " sdk=" + Build.VERSION.SDK_INT
                        + " device=" + clean(Build.MANUFACTURER + " " + Build.MODEL)
                        + " build=" + clean(Build.DISPLAY));
    }

    private static String appVersion(Context context) {
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return version == null || version.trim().isEmpty() ? "unknown" : clean(version);
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    static void info(Context context, String component, String event, String detail) {
        write(context, "INFO", component, event, detail, null);
    }

    static void warn(Context context, String component, String event, String detail) {
        write(context, "WARN", component, event, detail, null);
    }

    static void error(Context context, String component, String event,
                      String detail, Throwable error) {
        write(context, "ERROR", component, event, detail, error);
    }

    static void export(Context context, Uri destination) throws Exception {
        synchronized (LOCK) {
            try (OutputStream raw = context.getContentResolver().openOutputStream(destination, "wt")) {
                if (raw == null) throw new IllegalStateException("无法打开所选下载位置");
                BufferedOutputStream output = new BufferedOutputStream(raw);
                output.write(("ScreenSecTranslator detailed log\n"
                        + "Exported: " + timestamp() + "\n"
                        + "Privacy: screenshots, OCR text, API tokens and passwords are never logged.\n"
                        + "================================================================================\n")
                        .getBytes(StandardCharsets.UTF_8));
                copyIfPresent(new File(context.getFilesDir(), PREVIOUS_NAME), output);
                copyIfPresent(new File(context.getFilesDir(), ACTIVE_NAME), output);
                output.flush();
            }
        }
    }

    private static void write(Context context, String level, String component,
                              String event, String detail, Throwable error) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                File active = new File(context.getFilesDir(), ACTIVE_NAME);
                rotateIfNeeded(context, active);
                StringBuilder line = new StringBuilder(256)
                        .append(timestamp()).append(' ')
                        .append('[').append(level).append("] ")
                        .append('[').append(clean(component)).append("] ")
                        .append('[').append(clean(event)).append("] ")
                        .append("thread=").append(clean(Thread.currentThread().getName()));
                if (detail != null && !detail.trim().isEmpty()) {
                    line.append(' ').append(clean(detail));
                }
                if (error != null) {
                    line.append(" error=").append(clean(error.getClass().getSimpleName()))
                            .append(':').append(clean(error.getMessage()));
                }
                line.append('\n');
                try (FileOutputStream output = new FileOutputStream(active, true)) {
                    output.write(line.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // Logging must never interrupt capture or translation.
            }
        }
    }

    private static void rotateIfNeeded(Context context, File active) {
        if (!active.exists() || active.length() < MAX_BYTES) return;
        File previous = new File(context.getFilesDir(), PREVIOUS_NAME);
        if (previous.exists()) previous.delete();
        active.renameTo(previous);
    }

    private static void copyIfPresent(File source, OutputStream output) throws Exception {
        if (!source.exists()) return;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return cleaned.length() > 800 ? cleaned.substring(0, 800) + "…" : cleaned;
    }
}
