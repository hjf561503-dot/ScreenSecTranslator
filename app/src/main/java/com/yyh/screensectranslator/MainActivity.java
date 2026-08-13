package com.yyh.screensectranslator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    static final String PREFS = "screen_sec_settings";
    static final String KEY_PROXY_URL = "proxy_url";
    static final String KEY_PROXY_TOKEN = "proxy_token";
    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_CAPTURE = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;
    private static final int REQ_EXPORT_LOG = 1004;
    private static final String LOG_EXPORT_PASSWORD = "20121013";
    private static final String FREE_DICTIONARY_URL =
            "https://raw.githubusercontent.com/hjf561503-dot/ScreenSecTranslator/"
                    + "refs/heads/agent/offline-realtime-v2/"
                    + "app/src/main/assets/cyber-security-en-zh.tsv";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private EditText proxyUrlInput;
    private EditText tokenInput;
    private TextView statusText;
    private TextView modelStatusText;
    private Button modelButton;
    private Button startButton;
    private boolean waitingToStart;
    private boolean modelOperationInProgress;
    private boolean startAfterModelReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.appStarted(this);
        setContentView(buildUi());
        CyberGlossary.loadDictionaries(this);
        refreshOfflineModelStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOfflineModelStatus();
    }

    private View buildUi() {
        int pad = dp(20);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(11, 16, 25));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(30), pad, dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = text("ON-DEMAND SCREEN TRANSLATOR", 12, Color.rgb(77, 225, 193));
        eyebrow.setLetterSpacing(0.12f);
        root.addView(eyebrow);

        TextView title = text("屏译·安全术语版 2.1.0", 30, Color.WHITE);
        title.setPadding(0, dp(8), 0, dp(8));
        root.addView(title);

        TextView intro = text(
                "不再后台自动扫描。短按悬浮球会在本机一次扫完整个当前页面，只翻译纯英文区域；按住满 3 秒才会尝试调用你配置的云端代理。",
                15,
                Color.rgb(188, 199, 216));
        intro.setLineSpacing(dp(4), 1f);
        root.addView(intro);

        statusText = text("尚未启动", 14, Color.rgb(255, 206, 107));
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusText.setBackground(rounded(Color.rgb(27, 35, 49), dp(12), Color.rgb(55, 67, 84)));
        root.addView(statusText, fullWidth(dp(20)));

        root.addView(sectionTitle("按需离线翻译"), fullWidth(dp(22)));

        TextView localTip = text(
                "短按一次只截取一帧，然后在这张画面内分批识别并翻译全部可辨认英文，直到整页完成。不会把中文片段送入翻译器，也不会在后台重复截图。",
                13,
                Color.rgb(151, 165, 185));
        localTip.setLineSpacing(dp(3), 1f);
        root.addView(localTip, fullWidth(dp(8)));

        modelButton = secondaryButton("检查离线中英翻译模型状态");
        modelButton.setOnClickListener(v -> downloadOfflineModel());
        root.addView(modelButton, fullWidth(dp(12)));

        modelStatusText = text("模型状态：正在检查设备…", 13,
                Color.rgb(255, 206, 107));
        root.addView(modelStatusText, fullWidth(dp(6)));

        TextView googleAttribution = text("由 Google 翻译提供支持 · 查看 Google 翻译", 13,
                Color.rgb(114, 197, 255));
        googleAttribution.setOnClickListener(v -> startActivity(new Intent(
                Intent.ACTION_VIEW, Uri.parse("https://translate.google.com"))));
        root.addView(googleAttribution, fullWidth(dp(8)));

        root.addView(sectionTitle("免费安全术语库"), fullWidth(dp(22)));
        TextView dictionaryTip = text(
                "APK 已内置小型网络安全术语库。可从本项目 GitHub 免费更新 TSV；只下载术语，不上传截图或 OCR 文字，也不需要 API 密钥。Dirb、DirBuster、Nmap 等工具名会保持原文。",
                13,
                Color.rgb(151, 165, 185));
        dictionaryTip.setLineSpacing(dp(3), 1f);
        root.addView(dictionaryTip, fullWidth(dp(6)));

        Button dictionaryButton = secondaryButton("更新免费安全术语库");
        dictionaryButton.setOnClickListener(v -> updateFreeDictionary(dictionaryButton));
        root.addView(dictionaryButton, fullWidth(dp(8)));

        root.addView(sectionTitle("按住 3 秒在线精译（可选）"), fullWidth(dp(22)));

        TextView onlineTip = text(
                "只有手指在悬浮球上保持满 3 秒、且没有拖动时，才会截取当前画面并尝试调用你自己的代理；普通短按永远只走本机。代理不可用时会明确报错，不会悄悄改走其他 API。",
                13,
                Color.rgb(151, 165, 185));
        onlineTip.setLineSpacing(dp(3), 1f);
        root.addView(onlineTip, fullWidth(dp(6)));

        proxyUrlInput = input("可选代理地址，例如 http://127.0.0.1:8787");
        proxyUrlInput.setSingleLine(true);
        proxyUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        proxyUrlInput.setText(prefs.getString(KEY_PROXY_URL, "http://127.0.0.1:8787"));
        root.addView(proxyUrlInput, fullWidth(dp(10)));

        tokenInput = input("代理口令（若服务端已配置）");
        tokenInput.setSingleLine(true);
        tokenInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        tokenInput.setText(prefs.getString(KEY_PROXY_TOKEN, ""));
        root.addView(tokenInput, fullWidth(dp(10)));

        Button testButton = secondaryButton("测试可选代理连接");
        testButton.setOnClickListener(v -> testProxy());
        root.addView(testButton, fullWidth(dp(10)));

        startButton = primaryButton("启动按需屏幕翻译（由 Google 翻译提供支持）");
        startButton.setOnClickListener(v -> beginStartFlow());
        root.addView(startButton, fullWidth(dp(16)));

        Button stopButton = secondaryButton("停止并关闭悬浮球");
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenTranslateService.class));
            statusText.setText("已停止并关闭悬浮球");
            statusText.setTextColor(Color.rgb(188, 199, 216));
        });
        root.addView(stopButton, fullWidth(dp(10)));

        root.addView(sectionTitle("详细日志"), fullWidth(dp(22)));
        Button logButton = secondaryButton("下载详细日志");
        logButton.setOnClickListener(v -> requestLogPassword());
        root.addView(logButton, fullWidth(dp(8)));

        root.addView(sectionTitle("使用方式"), fullWidth(dp(22)));
        TextView steps = text(
                "1. 第一次使用先下载离线模型，之后没有网络也能翻译。\n" +
                "2. 点击启动并授予悬浮窗、通知和系统录屏权限。\n" +
                "3. 切换到目标页面，短按悬浮球；本机会分批处理到整页英文全部完成。\n" +
                "4. 拖动悬浮球可改变位置；拖动不会触发翻译。\n" +
                "5. 只有连续按住悬浮球满 3 秒，才会向已配置的代理上传一次当前画面。",
                15,
                Color.rgb(214, 222, 234));
        steps.setLineSpacing(dp(6), 1f);
        steps.setPadding(dp(16), dp(16), dp(16), dp(16));
        steps.setBackground(rounded(Color.rgb(20, 27, 39), dp(14), Color.rgb(44, 55, 72)));
        root.addView(steps);

        TextView privacy = text(
                "隐私：短按模式的 OCR、翻译、术语修正和缓存全部在设备上完成。首次下载模型需要网络，但不会上传屏幕文字。按住满 3 秒后，截图会发送给你配置的代理；密码、支付和私密页面不要使用在线精译。机器翻译无法作出数学意义上的 100% 正确保证，应用会用术语表、严格校验和原标点恢复尽量避免错误。",
                13,
                Color.rgb(151, 165, 185));
        privacy.setLineSpacing(dp(4), 1f);
        root.addView(privacy, fullWidth(dp(18)));

        return scroll;
    }

    private void downloadOfflineModel() {
        startAfterModelReady = false;
        beginModelDownload();
    }

    private void beginStartFlow() {
        if (!saveSettings()) return;
        if (modelOperationInProgress) {
            startAfterModelReady = true;
            statusText.setText("正在等待离线模型下载完成；完成后会继续启动");
            statusText.setTextColor(Color.rgb(255, 206, 107));
            return;
        }
        startAfterModelReady = true;
        modelOperationInProgress = true;
        setModelCheckingUi();
        OfflineModelState.isDownloaded()
                .addOnSuccessListener(downloaded -> {
                    modelOperationInProgress = false;
                    if (Boolean.TRUE.equals(downloaded)) {
                        showModelReady();
                        continueStartFlow();
                    } else {
                        beginModelDownload();
                    }
                })
                .addOnFailureListener(error -> {
                    modelOperationInProgress = false;
                    showModelFailure("无法检查模型：" + compact(error.getMessage()));
                });
    }

    private void continueStartFlow() {
        startAfterModelReady = false;
        waitingToStart = true;
        if (!Settings.canDrawOverlays(this)) {
            statusText.setText("请允许本应用显示悬浮窗");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        requestNotificationThenCapture();
    }

    private void refreshOfflineModelStatus() {
        if (modelOperationInProgress || modelStatusText == null) return;
        modelOperationInProgress = true;
        setModelCheckingUi();
        OfflineModelState.isDownloaded()
                .addOnSuccessListener(downloaded -> {
                    modelOperationInProgress = false;
                    if (Boolean.TRUE.equals(downloaded)) {
                        showModelReady();
                        if (startAfterModelReady) continueStartFlow();
                    } else if (startAfterModelReady) {
                        beginModelDownload();
                    } else {
                        showModelMissing();
                    }
                })
                .addOnFailureListener(error -> {
                    modelOperationInProgress = false;
                    startAfterModelReady = false;
                    showModelFailure("检查失败：" + compact(error.getMessage()));
                });
    }

    private void beginModelDownload() {
        if (modelOperationInProgress) return;
        modelOperationInProgress = true;
        modelButton.setEnabled(false);
        modelButton.setText("正在下载离线模型…");
        modelStatusText.setText("模型状态：下载中（约 30MB；ML Kit 不提供百分比）");
        modelStatusText.setTextColor(Color.rgb(255, 206, 107));
        statusText.setText("正在下载离线中英模型；下载成功前不会申请录屏权限");
        statusText.setTextColor(Color.rgb(255, 206, 107));
        AppLog.info(this, "MODEL", "DOWNLOAD_STARTED", "source=remote_model_manager");
        OfflineModelState.download()
                .addOnSuccessListener(unused -> OfflineModelState.isDownloaded()
                        .addOnSuccessListener(downloaded -> {
                            modelOperationInProgress = false;
                            if (!Boolean.TRUE.equals(downloaded)) {
                                startAfterModelReady = false;
                                showModelFailure("下载任务结束，但设备仍未检测到模型");
                                return;
                            }
                            AppLog.info(this, "MODEL", "DOWNLOAD_VERIFIED", "downloaded=true");
                            showModelReady();
                            statusText.setText("离线模型已下载并验证，可以启动按需翻译");
                            statusText.setTextColor(Color.rgb(77, 225, 193));
                            if (startAfterModelReady) continueStartFlow();
                        })
                        .addOnFailureListener(error -> {
                            modelOperationInProgress = false;
                            startAfterModelReady = false;
                            showModelFailure("下载后校验失败：" + compact(error.getMessage()));
                        }))
                .addOnFailureListener(error -> {
                    modelOperationInProgress = false;
                    startAfterModelReady = false;
                    AppLog.error(this, "MODEL", "DOWNLOAD_FAILED", "", error);
                    showModelFailure("下载失败：" + compact(error.getMessage()));
                });
    }

    private void setModelCheckingUi() {
        modelButton.setEnabled(false);
        modelButton.setText("正在检查离线模型…");
        modelStatusText.setText("模型状态：正在读取设备中的实际下载状态");
        modelStatusText.setTextColor(Color.rgb(255, 206, 107));
    }

    private void showModelReady() {
        modelButton.setEnabled(false);
        modelButton.setText("离线模型已下载并验证（无需重复下载）");
        modelStatusText.setText("模型状态：已就绪；启动时不会再次下载");
        modelStatusText.setTextColor(Color.rgb(77, 225, 193));
    }

    private void showModelMissing() {
        modelButton.setEnabled(true);
        modelButton.setText("下载离线中英翻译模型（约 30MB）");
        modelStatusText.setText("模型状态：未下载；点击启动也会先下载并验证");
        modelStatusText.setTextColor(Color.rgb(255, 206, 107));
    }

    private void showModelFailure(String message) {
        modelButton.setEnabled(true);
        modelButton.setText("重新检查/下载离线模型");
        modelStatusText.setText("模型状态：" + message);
        modelStatusText.setTextColor(Color.rgb(255, 122, 122));
        statusText.setText(message);
        statusText.setTextColor(Color.rgb(255, 122, 122));
    }

    private void requestNotificationThenCapture() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        waitingToStart = false;
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        statusText.setText("请确认录屏授权；只有你主动点击时才读取一帧画面");
        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= 34) {
            captureIntent = manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            captureIntent = manager.createScreenCaptureIntent();
        }
        startActivityForResult(captureIntent, REQ_CAPTURE);
        AppLog.info(this, "ACTIVITY", "CAPTURE_PERMISSION_REQUESTED",
                "sdk=" + Build.VERSION.SDK_INT);
    }

    private void requestLogPassword() {
        EditText password = input("请输入日志下载密码");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        int horizontal = dp(20);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(horizontal, dp(8), horizontal, 0);
        holder.addView(password, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("下载详细日志")
                .setView(holder)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", null)
                .create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (!LOG_EXPORT_PASSWORD.contentEquals(password.getText())) {
                        password.setError("密码错误");
                        AppLog.warn(this, "LOG", "EXPORT_DENIED", "reason=wrong_password");
                        return;
                    }
                    dialog.dismiss();
                    Intent export = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    export.addCategory(Intent.CATEGORY_OPENABLE);
                    export.setType("text/plain");
                    export.putExtra(Intent.EXTRA_TITLE,
                            "ScreenSecTranslator-detailed-log-" + System.currentTimeMillis() + ".txt");
                    startActivityForResult(export, REQ_EXPORT_LOG);
                }));
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this) && waitingToStart) {
                requestNotificationThenCapture();
            } else {
                waitingToStart = false;
                statusText.setText("未获得悬浮窗权限，无法覆盖译文");
            }
            return;
        }
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                statusText.setText("未获得录屏权限，按需翻译没有启动");
                AppLog.warn(this, "ACTIVITY", "CAPTURE_PERMISSION_DENIED",
                        "resultCode=" + resultCode + " data=" + (data != null));
                return;
            }
            Intent service = new Intent(this, ScreenTranslateService.class);
            service.putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(ScreenTranslateService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            statusText.setText("已启动：短按本地翻译整页；按住 3 秒尝试云端精译");
            statusText.setTextColor(Color.rgb(77, 225, 193));
            AppLog.info(this, "ACTIVITY", "SERVICE_START_REQUESTED",
                    "mode=on_demand");
            return;
        }
        if (requestCode == REQ_EXPORT_LOG && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri destination = data.getData();
            networkExecutor.execute(() -> {
                try {
                    AppLog.info(this, "LOG", "EXPORT_STARTED", "destination=system_document_picker");
                    AppLog.export(this, destination);
                    runOnUiThread(() -> Toast.makeText(this,
                            "详细日志已下载", Toast.LENGTH_LONG).show());
                } catch (Exception error) {
                    AppLog.error(this, "LOG", "EXPORT_FAILED", "", error);
                    runOnUiThread(() -> Toast.makeText(this,
                            "日志下载失败：" + compact(error.getMessage()), Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && waitingToStart) requestScreenCapture();
    }

    private boolean saveSettings() {
        String url = normalizedUrl(proxyUrlInput.getText().toString());
        if (url == null) url = "http://127.0.0.1:8787";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_PROXY_URL, url)
                .putString(KEY_PROXY_TOKEN, tokenInput.getText().toString().trim())
                .apply();
        proxyUrlInput.setText(url);
        return true;
    }

    private void testProxy() {
        String baseUrl = normalizedUrl(proxyUrlInput.getText().toString());
        if (baseUrl == null) {
            proxyUrlInput.setError("请输入有效的代理地址");
            return;
        }
        String token = tokenInput.getText().toString().trim();
        statusText.setText("正在测试可选代理连接…");
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(baseUrl + "/health").openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestMethod("GET");
                if (!token.isEmpty()) connection.setRequestProperty("X-App-Token", token);
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String body = readText(stream);
                runOnUiThread(() -> {
                    statusText.setText(status >= 200 && status < 300
                            ? "可选代理连接正常；只有按住满 3 秒才会使用它"
                            : "代理返回错误：HTTP " + status + " " + compact(body));
                    statusText.setTextColor(status >= 200 && status < 300
                            ? Color.rgb(77, 225, 193) : Color.rgb(255, 122, 122));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    statusText.setText("无法连接代理：" + compact(error.getMessage()));
                    statusText.setTextColor(Color.rgb(255, 122, 122));
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void updateFreeDictionary(Button button) {
        button.setEnabled(false);
        button.setText("正在下载术语库…");
        statusText.setText("正在从项目 GitHub 下载免费术语 TSV；不会上传屏幕文字");
        statusText.setTextColor(Color.rgb(255, 206, 107));
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(FREE_DICTIONARY_URL).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "text/plain");
                connection.setRequestProperty("User-Agent", "ScreenSecTranslator/2.1.0");
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String body = readText(stream, status >= 200 && status < 300
                        ? 512 * 1024 : 4096);
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("HTTP " + status + " " + compact(body));
                }
                CyberGlossary.DictionarySummary summary =
                        CyberGlossary.installOnlineUpdate(this, body);
                AppLog.info(this, "DICTIONARY", "ONLINE_UPDATE_COMPLETED",
                        "version=" + summary.version + " translations=" + summary.translations
                                + " protected_names=" + summary.protectedNames);
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText("重新检查免费安全术语库");
                    statusText.setText("安全术语库已更新：" + summary.version
                            + "；译词 " + summary.translations
                            + " 条，保护工具名 " + summary.protectedNames + " 条");
                    statusText.setTextColor(Color.rgb(77, 225, 193));
                });
            } catch (Exception error) {
                AppLog.error(this, "DICTIONARY", "ONLINE_UPDATE_FAILED", "", error);
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText("重试更新免费安全术语库");
                    statusText.setText("在线术语库更新失败，继续使用 APK 内置版本："
                            + compact(error.getMessage()));
                    statusText.setTextColor(Color.rgb(255, 122, 122));
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static String readText(InputStream stream) throws Exception {
        return readText(stream, 4096);
    }

    private static String readText(InputStream stream, int limit) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() + line.length() + 1 > limit) {
                    throw new IllegalStateException("响应内容超过限制");
                }
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String compact(String value) {
        if (value == null || value.trim().isEmpty()) return "未知错误";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
    }

    static String normalizedUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!(value.startsWith("http://") || value.startsWith("https://"))) return null;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.length() > 8 ? value : null;
    }

    private EditText input(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setHintTextColor(Color.rgb(119, 135, 158));
        edit.setTextColor(Color.WHITE);
        edit.setTextSize(15);
        edit.setPadding(dp(14), dp(13), dp(14), dp(13));
        edit.setBackground(rounded(Color.rgb(18, 25, 37), dp(12), Color.rgb(53, 66, 85)));
        return edit;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 17, Color.WHITE);
        view.setGravity(Gravity.BOTTOM);
        return view;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(Color.rgb(6, 25, 23));
        button.setAllCaps(false);
        button.setBackground(rounded(Color.rgb(77, 225, 193), dp(12), Color.rgb(77, 225, 193)));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(Color.rgb(218, 226, 238));
        button.setAllCaps(false);
        button.setBackground(rounded(Color.rgb(27, 35, 49), dp(12), Color.rgb(64, 78, 99)));
        return button;
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams fullWidth(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private GradientDrawable rounded(int fill, float radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
