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
import android.os.SystemClock;
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
    private static final int MAX_CAPTURE_RETRIES = 40;
    private static final long CAPTURE_RETRY_MS = 75L;
    private static final long LONG_PRESS_MS = 3_000L;
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
    private volatile Bitmap overlayFrame;
    private int captureWidth;
    private int captureHeight;
    private int captureDensity;
    private boolean modelReady;
    private boolean preparingModel;
    private boolean destroyed;
    private volatile boolean awaitingFrame;
    private boolean firstFrameObserved;
    private CaptureMode pendingCaptureMode;
    private int pendingCaptureAttempt;
    private long captureRequestStartedAt;
    private int consecutiveCaptureFailures;
    private long captureBackoffUntil;
    private boolean loggedSameSizeCallback;

    private final Runnable frameRetry = this::acquirePendingFrame;
    private final Runnable configurationResize = this::resizeCaptureSurfaceFromMetrics;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            if (destroyed) return;
            AppLog.warn(ScreenTranslateService.this, "CAPTURE", "PROJECTION_STOPPED",
                    "system_callback=true");
            mainHandler.post(() -> {
                toast("录屏授权已结束，按需翻译已停止");
                stopSelf();
            });
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            if (width <= 0 || height <= 0) return;
            int density = getResources().getConfiguration().densityDpi;
            if (width == captureWidth && height == captureHeight && density == captureDensity) {
                if (!loggedSameSizeCallback) {
                    loggedSameSizeCallback = true;
                    AppLog.info(ScreenTranslateService.this, "CAPTURE",
                            "CONTENT_RESIZE_IGNORED",
                            "reason=same_dimensions width=" + width + " height=" + height
                                    + " density=" + density);
                }
                return;
            }
            AppLog.info(ScreenTranslateService.this, "CAPTURE", "CONTENT_RESIZED",
                    "old_width=" + captureWidth + " old_height=" + captureHeight
                            + " new_width=" + width + " new_height=" + height
                            + " density=" + density);
            resizeCaptureSurface(width, height, density);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        captureThread = new HandlerThread("ScreenSec-Capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        offlineEngine = new OfflineTranslationEngine(this);
        createNotificationChannel();
        AppLog.info(this, "SERVICE", "CREATED", "capture_thread=started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            AppLog.warn(this, "SERVICE", "START_REJECTED", "reason=missing_projection_data");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundForProjection();
        if (!Settings.canDrawOverlays(this)) {
            AppLog.warn(this, "SERVICE", "START_REJECTED", "reason=overlay_permission_missing");
            toast("缺少悬浮窗权限");
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureBubble();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) {
            AppLog.warn(this, "SERVICE", "START_REJECTED", "reason=null_projection_data");
            toast("录屏授权数据无效，请重新启用");
            stopSelf();
            return START_NOT_STICKY;
        }
        beginProjection(resultCode, resultData);
        prepareOfflineModel();
        AppLog.info(this, "SERVICE", "STARTED", "mode=on_demand");
        return START_NOT_STICKY;
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
                    AppLog.info(ScreenTranslateService.this, "MODEL", "READY", "offline=true");
                    setBubbleState("译", Color.rgb(77, 225, 193));
                    toast("离线翻译模型已就绪");
                });
            }

            @Override
            public void onFailure(Exception error) {
                AppLog.error(ScreenTranslateService.this, "MODEL", "PREPARE_FAILED", "", error);
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
        String detail = "短按本机翻译整页；按住 3 秒尝试云端精译";
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("屏译按需翻译模式")
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
                "按需屏幕翻译",
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
            AppLog.warn(this, "CAPTURE", "PROJECTION_CREATE_FAILED",
                    "result_code=" + resultCode);
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
        AppLog.info(this, "CAPTURE", "PROJECTION_CREATED",
                "width=" + captureWidth + " height=" + captureHeight
                        + " density=" + captureDensity + " display=" + (virtualDisplay != null));
    }

    private ImageReader newImageReader() {
        ImageReader reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                4);
        reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        AppLog.info(this, "CAPTURE", "IMAGE_READER_CREATED",
                "width=" + captureWidth + " height=" + captureHeight
                        + " format=RGBA_8888 max_images=4");
        return reader;
    }

    private void onImageAvailable(ImageReader readyReader) {
        if (destroyed || readyReader != imageReader) return;
        if (!firstFrameObserved) {
            firstFrameObserved = true;
            AppLog.info(this, "CAPTURE", "FIRST_FRAME_AVAILABLE", "reader_callback=true");
        }
        if (awaitingFrame) {
            captureHandler.removeCallbacks(frameRetry);
            acquirePendingFrame();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mainHandler.removeCallbacks(configurationResize);
        mainHandler.postDelayed(configurationResize, 250);
    }

    private void resizeCaptureSurfaceFromMetrics() {
        int width;
        int height;
        int density = getResources().getConfiguration().densityDpi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
        } else {
            DisplayMetrics real = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(real);
            width = real.widthPixels;
            height = real.heightPixels;
            density = real.densityDpi;
        }
        resizeCaptureSurface(width, height, density);
    }

    private void resizeCaptureSurface(int width, int height, int density) {
        if (virtualDisplay == null || width <= 0 || height <= 0) return;
        if (width == captureWidth && height == captureHeight && density == captureDensity) {
            return;
        }
        busy.set(false);
        awaitingFrame = false;
        captureHandler.removeCallbacks(frameRetry);
        if (offlineEngine != null) offlineEngine.clearPage();
        firstFrameObserved = false;
        removeTranslationOverlay();
        captureWidth = width;
        captureHeight = height;
        captureDensity = density;
        loggedSameSizeCallback = false;
        virtualDisplay.setSurface(null);
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
        }
        imageReader = newImageReader();
        virtualDisplay.resize(captureWidth, captureHeight, captureDensity);
        virtualDisplay.setSurface(imageReader.getSurface());
        AppLog.info(this, "CAPTURE", "SURFACE_RESIZED",
                "width=" + captureWidth + " height=" + captureHeight
                        + " density=" + captureDensity);
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
        bubbleView.setContentDescription("短按本地翻译整页；按住三秒在线精译；拖动改变位置");
        bubbleView.setBackground(circle(Color.rgb(255, 202, 92), Color.WHITE));

        bubbleParams = new WindowManager.LayoutParams(
                dp(58),
                dp(58),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
        private boolean moved;
        private boolean longPressTriggered;
        private final Runnable triggerLongPress = () -> {
            if (moved || longPressTriggered) return;
            longPressTriggered = true;
            AppLog.info(ScreenTranslateService.this, "BUBBLE", "LONG_PRESS_TRIGGERED",
                    "threshold_ms=" + LONG_PRESS_MS);
            requestOnlineRefinement();
        };

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    moved = false;
                    longPressTriggered = false;
                    mainHandler.removeCallbacks(triggerLongPress);
                    mainHandler.postDelayed(triggerLongPress, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (Math.hypot(dx, dy) > dp(8)) {
                        moved = true;
                        mainHandler.removeCallbacks(triggerLongPress);
                    }
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
                    mainHandler.removeCallbacks(triggerLongPress);
                    if (!moved && !longPressTriggered) {
                        AppLog.info(ScreenTranslateService.this, "BUBBLE", "TAPPED",
                                "model_ready=" + modelReady + " busy=" + busy.get());
                        requestLocalTranslation();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mainHandler.removeCallbacks(triggerLongPress);
                    return true;
                default:
                    return false;
            }
        }
    }

    private void requestLocalTranslation() {
        if (destroyed || mediaProjection == null || imageReader == null) {
            toast("录屏会话尚未就绪");
            return;
        }
        if (!modelReady) {
            prepareOfflineModel();
            toast("正在准备离线翻译模型");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            toast("正在处理当前页面，请稍候");
            return;
        }
        setBubbleState("…", Color.rgb(255, 202, 92));
        hideOverlayForCapture();
        AppLog.info(this, "CAPTURE", "REQUESTED", "mode=local user=true");
        captureHandler.postDelayed(
                () -> beginFrameAcquire(CaptureMode.LOCAL), 55);
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
        setBubbleState("AI", Color.rgb(126, 173, 255));
        hideOverlayForCapture();
        AppLog.info(this, "CAPTURE", "REQUESTED", "mode=online force=true user=true");
        captureHandler.postDelayed(
                () -> beginFrameAcquire(CaptureMode.ONLINE), 55);
    }

    private void hideOverlayForCapture() {
        if (translationOverlay != null) translationOverlay.setVisibility(View.INVISIBLE);
    }

    private void restoreOverlayAfterCapture() {
        mainHandler.post(() -> {
            if (translationOverlay != null) translationOverlay.setVisibility(View.VISIBLE);
        });
    }

    private void beginFrameAcquire(CaptureMode mode) {
        pendingCaptureMode = mode;
        pendingCaptureAttempt = 0;
        captureRequestStartedAt = SystemClock.elapsedRealtime();
        awaitingFrame = true;
        acquirePendingFrame();
    }

    private void acquirePendingFrame() {
        if (!awaitingFrame) return;
        if (destroyed || imageReader == null) {
            awaitingFrame = false;
            fail("截屏通道已关闭");
            return;
        }
        Image image;
        try {
            image = imageReader.acquireLatestImage();
        } catch (IllegalStateException error) {
            awaitingFrame = false;
            restoreOverlayAfterCapture();
            AppLog.error(this, "CAPTURE", "ACQUIRE_FAILED", "", error);
            fail("读取屏幕画面失败：" + safeMessage(error));
            return;
        }
        if (image == null) {
            pendingCaptureAttempt++;
            if (pendingCaptureAttempt <= MAX_CAPTURE_RETRIES) {
                if (pendingCaptureAttempt == 1 || pendingCaptureAttempt % 10 == 0) {
                    AppLog.warn(this, "CAPTURE", "WAITING_FOR_FRAME",
                            "attempt=" + pendingCaptureAttempt + " max=" + MAX_CAPTURE_RETRIES);
                }
                captureHandler.postDelayed(frameRetry, CAPTURE_RETRY_MS);
            } else {
                awaitingFrame = false;
                restoreOverlayAfterCapture();
                consecutiveCaptureFailures++;
                long backoff = Math.min(30_000L,
                        4_000L * (1L << Math.min(3, consecutiveCaptureFailures - 1)));
                captureBackoffUntil = System.currentTimeMillis() + backoff;
                AppLog.warn(this, "CAPTURE", "FRAME_TIMEOUT",
                        "wait_ms=" + (SystemClock.elapsedRealtime() - captureRequestStartedAt)
                                + " first_frame_seen=" + firstFrameObserved
                                + " reader=" + (imageReader != null)
                                + " display=" + (virtualDisplay != null)
                                + " consecutive_failures=" + consecutiveCaptureFailures
                                + " retry_backoff_ms=" + backoff);
                fail("暂时没有取得屏幕画面", consecutiveCaptureFailures == 1);
            }
            return;
        }

        awaitingFrame = false;
        captureHandler.removeCallbacks(frameRetry);
        consecutiveCaptureFailures = 0;
        captureBackoffUntil = 0L;
        CaptureMode mode = pendingCaptureMode;

        Bitmap bitmap;
        try {
            bitmap = imageToBitmap(image);
        } catch (Exception error) {
            int failedWidth = image.getWidth();
            int failedHeight = image.getHeight();
            image.close();
            restoreOverlayAfterCapture();
            AppLog.error(this, "CAPTURE", "FRAME_CONVERSION_FAILED",
                    "image_width=" + failedWidth + " image_height=" + failedHeight, error);
            fail("处理截图失败：" + safeMessage(error));
            return;
        }
        image.close();
        restoreOverlayAfterCapture();
        AppLog.info(this, "CAPTURE", "FRAME_ACQUIRED",
                "wait_ms=" + (SystemClock.elapsedRealtime() - captureRequestStartedAt)
                        + " attempts=" + pendingCaptureAttempt
                        + " width=" + bitmap.getWidth() + " height=" + bitmap.getHeight());

        if (mode == CaptureMode.ONLINE) {
            encodeAndSendOnline(bitmap);
            return;
        }

        translateNewLocalPage(bitmap);
    }

    private void translateNewLocalPage(Bitmap bitmap) {
        offlineEngine.translateNewPage(bitmap, new OfflineTranslationEngine.TranslationCallback() {
            @Override
            public void onSuccess(OfflineTranslationEngine.PageProgress progress) {
                showLocalProgress(progress, bitmap);
            }

            @Override
            public void onFailure(Exception error) {
                recycleIfUnowned(bitmap);
                fail("离线识别失败：" + safeMessage(error));
            }
        });
    }

    private void continueStaticPage(Bitmap bitmap) {
        offlineEngine.continuePage(new OfflineTranslationEngine.TranslationCallback() {
            @Override
            public void onSuccess(OfflineTranslationEngine.PageProgress progress) {
                showLocalProgress(progress, bitmap);
            }

            @Override
            public void onFailure(Exception error) {
                recycleIfUnowned(bitmap);
                fail("继续翻译当前页面失败：" + safeMessage(error));
            }
        });
    }

    private void showLocalProgress(OfflineTranslationEngine.PageProgress progress, Bitmap bitmap) {
        mainHandler.post(() -> {
            if (destroyed) {
                recycleIfUnowned(bitmap);
                return;
            }
            if (!progress.translations.isEmpty()) {
                updateTranslationOverlay(progress.translations, LOCAL_ATTRIBUTION, bitmap);
            }
            AppLog.info(this, "TRANSLATE", "PAGE_PROGRESS_SHOWN",
                    "processed=" + progress.processedLines + " total=" + progress.totalLines
                            + " visible=" + progress.translations.size()
                            + " complete=" + progress.complete);
            if (!progress.complete) {
                setBubbleState(progress.processedLines + "/" + progress.totalLines,
                        Color.rgb(255, 202, 92));
                continueStaticPage(bitmap);
                return;
            }
            if (progress.translations.isEmpty()) {
                recycleIfUnowned(bitmap);
                removeTranslationOverlay();
                toast("当前页面没有可翻译的纯英文文字");
            }
            finishRequest();
        });
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        if (pixelStride <= 0 || rowStride < pixelStride * width) {
            throw new IllegalStateException("无效的屏幕帧步幅");
        }
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + rowPadding / pixelStride;
        plane.getBuffer().rewind();
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(plane.getBuffer());
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void encodeAndSendOnline(Bitmap bitmap) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output);
            String base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            networkExecutor.execute(() -> translateWithProxy(base64, width, height, bitmap));
        } catch (RuntimeException error) {
            recycleIfUnowned(bitmap);
            fail("准备在线精译画面失败：" + safeMessage(error));
        }
    }

    private void translateWithProxy(String base64, int width, int height, Bitmap bitmap) {
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
                    if (item == null || CyberGlossary.containsHan(item.source)
                            || !CyberGlossary.containsEnglish(item.source)) continue;
                    String translated = CyberGlossary.sanitizeTranslation(
                            item.source, item.translated);
                    ScreenTranslation safe = ScreenTranslation.of(
                            item.source, translated, item.x, item.y, item.width, item.height);
                    if (safe != null) translations.add(safe);
                }
            }
            String provider = response.optString("provider", "AI");
            mainHandler.post(() -> {
                if (destroyed) {
                    recycleIfUnowned(bitmap);
                    return;
                }
                if (translations.isEmpty()) {
                    recycleIfUnowned(bitmap);
                    toast("在线精译没有识别到英文");
                } else {
                    updateTranslationOverlay(translations, onlineAttribution(provider), bitmap);
                    toast("在线精译完成");
                }
                finishRequest();
            });
        } catch (Exception error) {
            recycleIfUnowned(bitmap);
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

    private void updateTranslationOverlay(List<ScreenTranslation> translations, String attribution,
                                          Bitmap bitmap) {
        if (translations == null || translations.isEmpty()) {
            removeTranslationOverlay();
            recycleIfUnowned(bitmap);
            return;
        }
        try {
            if (translationOverlay == null) {
                TranslationOverlayView candidate = new TranslationOverlayView(this);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    params.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                windowManager.addView(candidate, params);
                translationOverlay = candidate;
            }
            translationOverlay.setVisibility(View.VISIBLE);
            Bitmap previous = overlayFrame;
            overlayFrame = bitmap;
            translationOverlay.setTranslations(translations, attribution, bitmap);
            if (previous != null && previous != bitmap && !previous.isRecycled()) {
                previous.recycle();
            }
            AppLog.info(this, "OVERLAY", "UPDATED",
                    "translations=" + translations.size() + " attribution=" + attribution);
        } catch (RuntimeException error) {
            AppLog.error(this, "OVERLAY", "UPDATE_FAILED_KEEPING_BUBBLE",
                    "translations=" + translations.size(), error);
            removeTranslationOverlay();
            toast("译文覆盖层创建失败；悬浮球仍会保留");
        }
    }

    private void removeTranslationOverlay() {
        if (translationOverlay != null) {
            try {
                windowManager.removeView(translationOverlay);
            } catch (IllegalArgumentException ignored) {
            }
            translationOverlay = null;
        }
        Bitmap previous = overlayFrame;
        overlayFrame = null;
        if (previous != null && !previous.isRecycled()) previous.recycle();
    }

    private void recycleIfUnowned(Bitmap bitmap) {
        if (bitmap != null && bitmap != overlayFrame && !bitmap.isRecycled()) bitmap.recycle();
    }

    private void finishRequest() {
        busy.set(false);
        setBubbleState("译", Color.rgb(77, 225, 193));
    }

    private void setBubbleState(String label, int color) {
        Runnable update = () -> {
            if (destroyed || bubbleView == null) return;
            bubbleView.setVisibility(View.VISIBLE);
            bubbleView.setText(label);
            bubbleView.setBackground(circle(color, Color.WHITE));
        };
        if (Looper.myLooper() == Looper.getMainLooper()) update.run();
        else mainHandler.post(update);
    }

    private void fail(String message) {
        fail(message, true);
    }

    private void fail(String message, boolean showToast) {
        AppLog.warn(this, "SERVICE", "REQUEST_FAILED", "message=" + message);
        mainHandler.post(() -> {
            if (destroyed) return;
            busy.set(false);
            if (translationOverlay != null) translationOverlay.setVisibility(View.VISIBLE);
            if (showToast) {
                setBubbleState("!", Color.rgb(255, 108, 108));
                toast(message);
                mainHandler.postDelayed(() -> {
                    setBubbleState("译", Color.rgb(77, 225, 193));
                }, 1600);
            } else {
                setBubbleState("译", Color.rgb(77, 225, 193));
            }
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
        awaitingFrame = false;
        if (captureHandler != null) captureHandler.removeCallbacks(frameRetry);
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
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
        AppLog.info(this, "SERVICE", "DESTROYING",
                "busy=" + busy.get() + " model_ready=" + modelReady);
        destroyed = true;
        busy.set(false);
        awaitingFrame = false;
        if (captureHandler != null) captureHandler.removeCallbacks(frameRetry);
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
