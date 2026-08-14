package com.yyh.screensectranslator;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.nl.translate.TranslateRemoteModel;

final class OfflineModelState {
    private static final String WARM_UP_TEXT = "Network security.";
    private static final TranslateRemoteModel CHINESE_MODEL =
            new TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build();
    private static volatile boolean runtimeVerified;

    private OfflineModelState() {
    }

    static Task<Boolean> isDownloaded() {
        return RemoteModelManager.getInstance().isModelDownloaded(CHINESE_MODEL);
    }

    static Task<Void> download() {
        runtimeVerified = false;
        return RemoteModelManager.getInstance().download(
                CHINESE_MODEL, new DownloadConditions.Builder().build());
    }

    static boolean isRuntimeVerified() {
        return runtimeVerified;
    }

    /**
     * Verifies more than the RemoteModelManager flag: the downloaded model must
     * actually load and complete one English-to-Chinese inference. This prevents
     * the UI from claiming that the model is ready while the runtime still has
     * a missing/corrupt model or an initialization failure.
     */
    static Task<Void> verifyReady() {
        if (runtimeVerified) return Tasks.forResult(null);

        TaskCompletionSource<Void> completion = new TaskCompletionSource<>();
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build();
        Translator verifier = Translation.getClient(options);
        isDownloaded()
                .addOnSuccessListener(downloaded -> {
                    if (!Boolean.TRUE.equals(downloaded)) {
                        verifier.close();
                        completion.setException(new IllegalStateException("离线中英模型尚未下载"));
                        return;
                    }
                    verifier.translate(WARM_UP_TEXT)
                            .addOnSuccessListener(output -> {
                                verifier.close();
                                if (output == null || output.trim().isEmpty()
                                        || !CyberGlossary.containsHan(output)) {
                                    completion.setException(new IllegalStateException(
                                            "离线模型运行验证未返回中文"));
                                    return;
                                }
                                runtimeVerified = true;
                                completion.setResult(null);
                            })
                            .addOnFailureListener(error -> {
                                verifier.close();
                                completion.setException(error);
                            });
                })
                .addOnFailureListener(error -> {
                    verifier.close();
                    completion.setException(error);
                });
        return completion.getTask();
    }
}
