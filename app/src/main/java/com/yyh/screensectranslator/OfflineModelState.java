package com.yyh.screensectranslator;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;

final class OfflineModelState {
    private static final TranslateRemoteModel CHINESE_MODEL =
            new TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build();

    private OfflineModelState() {
    }

    static Task<Boolean> isDownloaded() {
        return RemoteModelManager.getInstance().isModelDownloaded(CHINESE_MODEL);
    }

    static Task<Void> download() {
        return RemoteModelManager.getInstance().download(
                CHINESE_MODEL, new DownloadConditions.Builder().build());
    }
}
