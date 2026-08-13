package com.yyh.screensectranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScreenTranslateService extends Service {
    static final String EXTRA_RESULT_CODE = "projection_result_code";
    static final String EXTRA_RESULT_DATA = "projection_result_data";

    private static final String CHANNEL_ID = "screen_translation_capture";
    private static final int NOTIFICATION_ID = 7301;
    private static final int MAX_CAPTURE_RETRIES = 10;
    private static final int UNCHANGED_HASH_DISTANCE = 0;
    private static final long LONG_PRESS_MS = 650L;
    private static final long ONLINE_RESULT_HOLD_MS = 10_000L;
    private static final String LOCAL_ATTRIBUTION = "由 Google 翻译提供支持";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private WindowManager windowManager;
    private TextView bubbleView;
    private WindowManager.LayoutParams bubbleParams;
    private TranslationOverlayView translationOverlay;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private OfflineTranslationEngine offlineEngine;
    private int captureWidth;
    private int captureHeight;
    private int captureDensity;
    private int realtimeIntervalMs;
    private boolean realtimeEnabled;
    private boolean onlineRefinementEnabled;
    private boolean modelReady;
    private boolean preparingModel;
    private boolean destroyed;
    private boolean hasFrameHash;
    private long lastFrameHash;
    private long suppressAutoUntil;

    private final Runnable realtimeTick = () -> {
        if (destroyed || !realtimeEnabled) return;
        if (System.currentTimeMillis() < suppressAutoUntil || busy.get()) {
            scheduleNextRealtime();
            return;
        }
        requestLocalTranslation(false, false);
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            if (destroyed) return;
            mainHandler.post(() -> {
                toast("录屏授权已结束，实时翻译已停止");
                stopSelf();
            });
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            mainHandler.post(() -> resizeCaptureSurface(width, height));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        captureThread = new HandlerThread("ScreenSec-Capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        offlineEngine = new OfflineTranslationEngine();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        loadSettings();
        startForegroundForProjection();
        if (!Settings.canDrawOverlays(this)) {
            toast("缺少悬浮窗权限");
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureBubble();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) {
            toast("录屏授权数据无效，请重新启用");
            stopSelf();
            return START_NOT_STICKY;
        }
        beginProjection(resultCode, resultData);
        prepareOfflineModel();
        return START_NOT_STICKY;
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        realtimeEnabled = prefs.getBoolean(MainActivity.KEY_REALTIME_ENABLED, true);
        onlineRefinementEnabled = prefs.getBoolean(MainActivity.KEY_ONLINE_REFINEMENT, false);
        realtimeIntervalMs = clamp(
                prefs.getInt(MainActivity.KEY_REALTIME_INTERVAL_MS, 1200),
                MainActivity.MIN_INTERVAL_MS,
                MainActivity.MAX_INTERVAL_MS);
    }

    private void prepareOfflineModel() {
        if (destroyed || modelReady || preparingModel) return;
        preparingModel = true;
        setBubbleState("↓", Color.rgb(255, 202, 92));
        offlineEngine.prepare(new OfflineTranslationEngine.PrepareCallback() {
            @Override
            public void onReady() {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    preparingModel = false;
                    modelReady = true;
                    setBubbleState(realtimeEnabled ? "实" : "译", Color.rgb(77, 225, 193));
                    toast("离线翻译模型已就绪");
                    if (realtimeEnabled) {
                        mainHandler.removeCallbacks(realtimeTick);
                        mainHandler.postDelayed(realtimeTick, 250);
                    }
                });
            }

            @Override
            public void onFailure(Exception error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    preparingModel = false;
                    modelReady = false;
                    setBubbleState("!", Color.rgb(255, 108, 108));
                    toast("离线模型未就绪：" + safeMessage(error));
                });
            }
        });
    }

    private void startForegroundForProjection() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String detail = realtimeEnabled
                ? "本机自动识别并翻译；画面不会上传"
                : "点击悬浮球，在本机识别并翻译";
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("屏译离线实时模式")
                .setContentText(detail)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "离线实时屏幕翻译",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("维持由用户授权的本机屏幕识别会话");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void beginProjection(int resultCode, Intent resultData) {
        releaseProjection();
        updateCaptureSize();
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            toast("无法创建录屏会话");
            stopSelf();
            return;
        }
        mediaProjection.registerCallback(projectionCallback, mainHandler);
        imageReader = newImageReader();
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenSecTranslator-Realtime",
                captureWidth,
                captureHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);
    }

    private ImageReader newImageReader() {
        return ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                3);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mainHandler.postDelayed(this::resizeCaptureSurface, 250);
    }

    private void resizeCaptureSurface() {
        updateCaptureSize();
        resizeCaptureSurface(captureWidth, captureHeight);
    }

    private void resizeCaptureSurface(int width, int height) {
        if (virtualDisplay == null || width <= 0 || height <= 0) return;
        busy.set(false);
        hasFrameHash = false;
        removeTranslationOverlay();
        captureWidth = width;
        captureHeight = height;
        captureDensity = getResources().getConfiguration().densityDpi;
        virtualDisplay.setSurface(null);
        if (imageReader != null) imageReader.close();
        imageReader = newImageReader();
        virtualDisplay.resize(captureWidth, captureHeight, captureDensity);
        virtualDisplay.setSurface(imageReader.getSurface());
        scheduleNextRealtime();
    }

    private void updateCaptureSize() {
        captureDensity = getResources().getConfiguration().densityDpi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            captureWidth = bounds.width();
            captureHeight = bounds.height();
        } else {
            DisplayMetrics real = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(real);
            captureWidth = real.widthPixels;
            captureHeight = real.heightPixels;
            captureDensity = real.densityDpi;
        }
    }

    private void ensureBubble() {
        if (bubbleView != null) return;
        bubbleView = new TextView(this);
        bubbleView.setText("↓");
        bubbleView.setTextSize(20);
        bubbleView.setTextColor(Color.rgb(5, 28, 24));
        bubbleView.setGravity(Gravity.CENTER);
        bubbleView.setElevation(dp(10));
        bubbleView.setContentDescription("点击立即离线翻译；长按可选在线精译；拖动改变位置");
        bubbleView.setBackground(circle(Color.rgb(255, 202, 92), Color.WHITE));

        bubbleParams = new WindowManager.LayoutParams(
                dp(58),
                dp(58),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        updateCaptureSize();
        bubbleParams.x = Math.max(0, captureWidth - dp(78));
        bubbleParams.y = Math.max(dp(80), captureHeight / 3);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bubbleParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        bubbleView.setOnTouchListener(new BubbleTouchListener());
        windowManager.addView(bubbleView, bubbleParams);
    }

    private final class BubbleTouchListener implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private long downAt;
        private boolean moved;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    downAt = event.getEventTime();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (Math.hypot(dx, dy) > dp(8)) moved = true;
                    bubbleParams.x = clamp(startX + Math.round(dx), 0,
                            Math.max(0, captureWidth - bubbleParams.width));
                    bubbleParams.y = clamp(startY + Math.round(dy), 0,
                            Math.max(0, captureHeight - bubbleParams.height));
                    try {
                        windowManager.updateViewLayout(bubbleView, bubbleParams);
                    } catch (IllegalArgumentException ignored) {
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        long held = event.getEventTime() - downAt;
                        if (held >= LONG_PRESS_MS) {
                            if (onlineRefinementEnabled) requestOnlineRefinement();
                            else toast("在线精译未开启；实时翻译不会调用 API");
                        } else {
                            requestLocalTranslation(true, true);
                        }
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private void requestLocalTranslation(boolean force, boolean userInitiated) {
        if (destroyed || mediaProjection == null || imageReader == null) {
            if (userInitiated) toast("录屏会话尚未就绪");
            return;
        }
        if (!modelReady) {
            prepareOfflineModel();
            if (userInitiated) toast("正在准备离线翻译模型");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            if (userInitiated) toast("正在处理上一帧画面");
            return;
        }
        mainHandler.removeCallbacks(realtimeTick);
        setBubbleState("…", Color.rgb(255, 202, 92));
        hideOverlayForCapture();
        captureHandler.postDelayed(
                () -> acquireFrame(CaptureMode.LOCAL, force, 0), 55);
    }

    private void requestOnlineRefinement() {
        if (destroyed || mediaProjection == null || imageReader == null) {
            toast("录屏会话尚未就绪");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            toast("正在处理上一帧画面");
            return;
        }
        mainHandler.removeCallbacks(realtimeTick);
        setBubbleState("AI", Color.rgb(126, 173, 255));
        hideOverlayForCapture();
        captureHandler.postDelayed(
                () -> acquireFrame(CaptureMode.ONLINE, true, 0), 55);
    }

    private void hideOverlayForCapture() {
        if (translationOverlay != null) translationOverlay.setVisibility(View.INVISIBLE);
    }

    private void restoreOverlayAfterCapture() {
        mainHandler.post(() -> {
            if (translationOverlay != null) translationOverlay.setVisibility(View.VISIBLE);
        });
    }

    private void acquireFrame(CaptureMode mode, boolean force, int attempt) {
        if (destroyed || imageReader == null) {
            fail("截屏通道已关闭");
            return;
        }
        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            if (attempt < MAX_CAPTURE_RETRIES) {
                captureHandler.postDelayed(
                        () -> acquireFrame(mode, force, attempt + 1), 60);
            } else {
                restoreOverlayAfterCapture();
                fail("暂时没有取得屏幕画面");
            }
            return;
        }

        Bitmap bitmap;
        try {
            bitmap = imageToBitmap(image);
        } catch (Exception error) {
            image.close();
            restoreOverlayAfterCapture();
            fail("处理截图失败：" + safeMessage(error));
            return;
        }
        image.close();
        restoreOverlayAfterCapture();

        if (mode == CaptureMode.ONLINE) {
            encodeAndSendOnline(bitmap);
            return;
        }

        long fingerprint = FrameFingerprint.differenceHash(bitmap);
        if (!force && hasFrameHash
                && FrameFingerprint.distance(lastFrameHash, fingerprint) <= UNCHANGED_HASH_DISTANCE) {
            bitmap.recycle();
            mainHandler.post(this::finishWithoutVisualChange);
            return;
        }
        lastFrameHash = fingerprint;
        hasFrameHash = true;

        offlineEngine.translate(bitmap, new OfflineTranslationEngine.TranslationCallback() {
            @Override
            public void onSuccess(List<ScreenTranslation> translations) {
                bitmap.recycle();
                mainHandler.post(() -> {
                    if (destroyed) return;
                    updateTranslationOverlay(translations, LOCAL_ATTRIBUTION);
                    finishRequest();
                });
            }

            @Override
            public void onFailure(Exception error) {
                bitmap.recycle();
                fail("离线识别失败：" + safeMessage(error));
            }
        });
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * captureWidth;
        int paddedWidth = captureWidth + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(plane.getBuffer());
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void encodeAndSendOnline(Bitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output);
        bitmap.recycle();
        String base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        int width = captureWidth;
        int height = captureHeight;
        networkExecutor.execute(() -> translateWithProxy(base64, width, height));
    }

    private void translateWithProxy(String base64, int width, int height) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String proxyUrl = prefs.getString(MainActivity.KEY_PROXY_URL, "http://127.0.0.1:8787");
        String token = prefs.getString(MainActivity.KEY_PROXY_TOKEN, "");
        HttpURLConnection connection = null;
        try {
            JSONObject request = new JSONObject();
            request.put("image_base64", base64);
            request.put("width", width);
            request.put("height", height);
            request.put("provider", "auto");
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection) new URL(proxyUrl + "/translate").openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(120_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("X-App-Token", token);
            }
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body);
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseBody = readAll(stream, 2_000_000);
            JSONObject response = new JSONObject(responseBody);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(response.optString("error", "代理错误 HTTP " + status));
            }

            JSONArray array = response.optJSONArray("translations");
            List<ScreenTranslation> translations = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    ScreenTranslation item = ScreenTranslation.fromJson(array.optJSONObject(i));
                    if (item != null) translations.add(item);
                }
            }
            String provider = response.optString("provider", "AI");
            mainHandler.post(() -> {
                if (destroyed) return;
                suppressAutoUntil = System.currentTimeMillis() + ONLINE_RESULT_HOLD_MS;
                if (translations.isEmpty()) {
                    toast("在线精译没有识别到英文");
                } else {
                    updateTranslationOverlay(translations, onlineAttribution(provider));
                    toast("在线精译完成；10 秒后恢复离线自动翻译");
                }
                finishRequest();
            });
        } catch (Exception error) {
            fail("在线精译失败：" + safeMessage(error));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String onlineAttribution(String provider) {
        if (provider == null) return "AI 在线精译";
        if (provider.toLowerCase().contains("gemini")) return "Gemini 在线精译";
        if (provider.toLowerCase().contains("openai")) return "OpenAI 在线精译";
        return "AI 在线精译";
    }

    private void updateTranslationOverlay(List<ScreenTranslation> translations, String attribution) {
        if (translations == null || translations.isEmpty()) {
            removeTranslationOverlay();
            return;
        }
        if (translationOverlay == null) {
            translationOverlay = new TranslationOverlayView(this);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            windowManager.addView(translationOverlay, params);
            bringBubbleToFront();
        }
        translationOverlay.setVisibility(View.VISIBLE);
        translationOverlay.setTranslations(translations, attribution);
    }

    private void bringBubbleToFront() {
        if (bubbleView == null) return;
        try {
            windowManager.removeView(bubbleView);
            windowManager.addView(bubbleView, bubbleParams);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void removeTranslationOverlay() {
        if (translationOverlay == null) return;
        try {
            windowManager.removeView(translationOverlay);
        } catch (IllegalArgumentException ignored) {
        }
        translationOverlay = null;
    }

    private void finishWithoutVisualChange() {
        if (destroyed) return;
        busy.set(false);
        setBubbleState(realtimeEnabled ? "实" : "译", Color.rgb(77, 225, 193));
        scheduleNextRealtime();
    }

    private void finishRequest() {
        busy.set(false);
        setBubbleState(realtimeEnabled ? "实" : "译", Color.rgb(77, 225, 193));
        scheduleNextRealtime();
    }

    private void scheduleNextRealtime() {
        mainHandler.removeCallbacks(realtimeTick);
        if (destroyed || !realtimeEnabled || !modelReady) return;
        long delay = realtimeIntervalMs;
        long hold = suppressAutoUntil - System.currentTimeMillis();
        if (hold > delay) delay = hold;
        mainHandler.postDelayed(realtimeTick, Math.max(100L, delay));
    }

    private void setBubbleState(String label, int color) {
        mainHandler.post(() -> {
            if (destroyed || bubbleView == null) return;
            bubbleView.setVisibility(View.VISIBLE);
            bubbleView.setText(label);
            bubbleView.setBackground(circle(color, Color.WHITE));
        });
    }

    private void fail(String message) {
        mainHandler.post(() -> {
            if (destroyed) return;
            busy.set(false);
            if (translationOverlay != null) translationOverlay.setVisibility(View.VISIBLE);
            setBubbleState("!", Color.rgb(255, 108, 108));
            toast(message);
            mainHandler.postDelayed(() -> {
                setBubbleState(realtimeEnabled ? "实" : "译", Color.rgb(77, 225, 193));
                scheduleNextRealtime();
            }, 1600);
        });
    }

    private static String readAll(InputStream stream, int limit) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = stream.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IllegalStateException("代理响应过大");
            out.write(buffer, 0, count);
        }
        stream.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 180 ? message.substring(0, 180) + "…" : message;
    }

    private GradientDrawable circle(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(fill);
        drawable.setStroke(dp(2), stroke);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void toast(String message) {
        mainHandler.post(() -> {
            if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    private void releaseProjection() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
                mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        busy.set(false);
        mainHandler.removeCallbacksAndMessages(null);
        removeTranslationOverlay();
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (IllegalArgumentException ignored) {
            }
            bubbleView = null;
        }
        releaseProjection();
        if (offlineEngine != null) offlineEngine.close();
        networkExecutor.shutdownNow();
        if (captureThread != null) captureThread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private enum CaptureMode {
        LOCAL,
        ONLINE
    }
}
