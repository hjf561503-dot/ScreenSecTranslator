package com.yyh.screensectranslator;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class OfflineTranslationEngine implements AutoCloseable {
    interface PrepareCallback {
        void onReady();

        void onFailure(Exception error);
    }

    interface TranslationCallback {
        void onSuccess(List<ScreenTranslation> translations);

        void onFailure(Exception error);
    }

    private static final int MAX_LINES = 80;
    private static final int MAX_CACHE_ENTRIES = 600;

    private final TextRecognizer recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS);
    private final Translator translator;
    private final Map<String, String> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });
    private volatile boolean modelReady;

    OfflineTranslationEngine() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build();
        translator = Translation.getClient(options);
    }

    void prepare(PrepareCallback callback) {
        if (modelReady) {
            callback.onReady();
            return;
        }
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    modelReady = true;
                    callback.onReady();
                })
                .addOnFailureListener(error -> callback.onFailure(asException(error)));
    }

    void translate(Bitmap bitmap, TranslationCallback callback) {
        if (!modelReady) {
            callback.onFailure(new IllegalStateException("离线翻译模型尚未准备完成"));
            return;
        }
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(input)
                .addOnSuccessListener(text -> translateRecognizedLines(
                        text, bitmap.getWidth(), bitmap.getHeight(), callback))
                .addOnFailureListener(error -> callback.onFailure(asException(error)));
    }

    private void translateRecognizedLines(Text recognized, int imageWidth, int imageHeight,
                                          TranslationCallback callback) {
        List<Candidate> candidates = new ArrayList<>();
        for (Text.TextBlock block : recognized.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (candidates.size() >= MAX_LINES) break;
                String source = line.getText() == null ? "" : line.getText().trim();
                Rect box = line.getBoundingBox();
                if (box == null || !CyberGlossary.shouldTranslate(source)) continue;
                candidates.add(new Candidate(source, box));
            }
            if (candidates.size() >= MAX_LINES) break;
        }
        if (candidates.isEmpty()) {
            callback.onSuccess(Collections.emptyList());
            return;
        }

        List<ScreenTranslation> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger remaining = new AtomicInteger(candidates.size());
        for (Candidate candidate : candidates) {
            String exact = CyberGlossary.exactTranslation(candidate.source);
            if (exact != null) {
                addResult(results, candidate, exact, imageWidth, imageHeight);
                finishOne(remaining, results, callback);
                continue;
            }

            String cached = cache.get(candidate.source);
            if (cached != null) {
                addResult(results, candidate, cached, imageWidth, imageHeight);
                finishOne(remaining, results, callback);
                continue;
            }

            CyberGlossary.ProtectedText protectedText = CyberGlossary.protect(candidate.source);
            translator.translate(protectedText.encoded)
                    .addOnSuccessListener(raw -> {
                        String translated = protectedText.restore(raw);
                        if (!translated.isEmpty()) cache.put(candidate.source, translated);
                        addResult(results, candidate, translated, imageWidth, imageHeight);
                        finishOne(remaining, results, callback);
                    })
                    .addOnFailureListener(error -> finishOne(remaining, results, callback));
        }
    }

    private static void addResult(List<ScreenTranslation> results, Candidate candidate,
                                  String translated, int imageWidth, int imageHeight) {
        if (translated == null || translated.trim().isEmpty()) return;
        if (normalize(candidate.source).equals(normalize(translated))) return;
        Rect box = candidate.box;
        ScreenTranslation result = ScreenTranslation.of(
                candidate.source,
                translated,
                box.left * 1000 / Math.max(1, imageWidth),
                box.top * 1000 / Math.max(1, imageHeight),
                Math.max(1, box.width() * 1000 / Math.max(1, imageWidth)),
                Math.max(1, box.height() * 1000 / Math.max(1, imageHeight)));
        if (result != null) results.add(result);
    }

    private static void finishOne(AtomicInteger remaining, List<ScreenTranslation> results,
                                  TranslationCallback callback) {
        if (remaining.decrementAndGet() != 0) return;
        List<ScreenTranslation> ordered = new ArrayList<>(results);
        ordered.sort(Comparator
                .comparingInt((ScreenTranslation item) -> item.y)
                .thenComparingInt(item -> item.x));
        callback.onSuccess(ordered);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\p{Punct}]", "").toLowerCase();
    }

    private static Exception asException(Exception error) {
        return error;
    }

    private static final class Candidate {
        final String source;
        final Rect box;

        Candidate(String source, Rect box) {
            this.source = source;
            this.box = new Rect(box);
        }
    }

    @Override
    public void close() {
        recognizer.close();
        translator.close();
        cache.clear();
    }
}
