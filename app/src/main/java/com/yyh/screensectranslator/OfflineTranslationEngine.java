package com.yyh.screensectranslator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OfflineTranslationEngine implements AutoCloseable {
    interface PrepareCallback {
        void onReady();

        void onFailure(Exception error);
    }

    interface TranslationCallback {
        void onSuccess(List<ScreenTranslation> translations);

        void onFailure(Exception error);
    }

    private static final int MAX_LINES = 60;
    private static final int MAX_OCR_LONG_EDGE = 1600;
    private static final int BATCH_LINES = 12;
    private static final int MAX_CACHE_ENTRIES = 600;
    private static final Pattern BATCH_SEGMENT = Pattern.compile(
            "(?is)QSEG(\\d{3})\\s*(.*?)(?=\\s*QSEG\\d{3}\\s*|$)");

    private final Context context;
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

    OfflineTranslationEngine(Context context) {
        this.context = context.getApplicationContext();
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
        long startedAt = SystemClock.elapsedRealtime();
        Bitmap ocrBitmap = scaledForOcr(bitmap);
        int ocrWidth = ocrBitmap.getWidth();
        int ocrHeight = ocrBitmap.getHeight();
        AppLog.info(context, "TRANSLATE", "OCR_STARTED",
                "source_width=" + bitmap.getWidth() + " source_height=" + bitmap.getHeight()
                        + " ocr_width=" + ocrWidth + " ocr_height=" + ocrHeight);
        InputImage input = InputImage.fromBitmap(ocrBitmap, 0);
        recognizer.process(input)
                .addOnSuccessListener(text -> {
                    if (ocrBitmap != bitmap) ocrBitmap.recycle();
                    long ocrFinishedAt = SystemClock.elapsedRealtime();
                    AppLog.info(context, "TRANSLATE", "OCR_COMPLETED",
                            "duration_ms=" + (ocrFinishedAt - startedAt)
                                    + " blocks=" + text.getTextBlocks().size());
                    translateRecognizedLines(text, ocrWidth, ocrHeight,
                            startedAt, ocrFinishedAt, callback);
                })
                .addOnFailureListener(error -> {
                    if (ocrBitmap != bitmap) ocrBitmap.recycle();
                    AppLog.error(context, "TRANSLATE", "OCR_FAILED",
                            "duration_ms=" + (SystemClock.elapsedRealtime() - startedAt), error);
                    callback.onFailure(asException(error));
                });
    }

    private void translateRecognizedLines(Text recognized, int imageWidth, int imageHeight,
                                          long startedAt, long ocrFinishedAt,
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
            AppLog.info(context, "TRANSLATE", "COMPLETED",
                    "duration_ms=" + (SystemClock.elapsedRealtime() - startedAt)
                            + " candidates=0 results=0");
            callback.onSuccess(Collections.emptyList());
            return;
        }

        List<ScreenTranslation> results = Collections.synchronizedList(new ArrayList<>());
        List<Candidate> unresolved = new ArrayList<>();
        int exactHits = 0;
        int cacheHits = 0;
        for (Candidate candidate : candidates) {
            String exact = CyberGlossary.exactTranslation(candidate.source);
            if (exact != null) {
                addResult(results, candidate, exact, imageWidth, imageHeight);
                exactHits++;
                continue;
            }

            String cached = cache.get(candidate.source);
            if (cached != null) {
                addResult(results, candidate, cached, imageWidth, imageHeight);
                cacheHits++;
                continue;
            }
            unresolved.add(candidate);
        }

        AppLog.info(context, "TRANSLATE", "CANDIDATES_READY",
                "candidates=" + candidates.size() + " exact_hits=" + exactHits
                        + " cache_hits=" + cacheHits + " unresolved=" + unresolved.size());
        if (unresolved.isEmpty()) {
            complete(results, startedAt, ocrFinishedAt, candidates.size(), 0, callback);
            return;
        }

        int batchCount = (unresolved.size() + BATCH_LINES - 1) / BATCH_LINES;
        AtomicInteger remainingBatches = new AtomicInteger(batchCount);
        for (int offset = 0; offset < unresolved.size(); offset += BATCH_LINES) {
            int end = Math.min(unresolved.size(), offset + BATCH_LINES);
            List<Candidate> batch = new ArrayList<>(unresolved.subList(offset, end));
            translateBatch(batch, imageWidth, imageHeight, results, remainingBatches,
                    startedAt, ocrFinishedAt, candidates.size(), batchCount, callback);
        }
    }

    private void translateBatch(List<Candidate> batch, int imageWidth, int imageHeight,
                                List<ScreenTranslation> results,
                                AtomicInteger remainingBatches,
                                long startedAt, long ocrFinishedAt,
                                int candidateCount, int batchCount,
                                TranslationCallback callback) {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) source.append('\n');
            source.append(String.format(java.util.Locale.ROOT, "QSEG%03d ", i))
                    .append(batch.get(i).source);
        }
        CyberGlossary.ProtectedText protectedText = CyberGlossary.protect(source.toString());
        translator.translate(protectedText.encoded)
                .addOnSuccessListener(raw -> {
                    String restored = protectedText.restore(raw);
                    Matcher matcher = BATCH_SEGMENT.matcher(restored);
                    int parsed = 0;
                    while (matcher.find()) {
                        int index;
                        try {
                            index = Integer.parseInt(matcher.group(1));
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        if (index < 0 || index >= batch.size()) continue;
                        Candidate candidate = batch.get(index);
                        String translated = matcher.group(2) == null
                                ? "" : matcher.group(2).trim();
                        if (translated.isEmpty()) continue;
                        cache.put(candidate.source, translated);
                        addResult(results, candidate, translated, imageWidth, imageHeight);
                        parsed++;
                    }
                    AppLog.info(context, "TRANSLATE", "BATCH_COMPLETED",
                            "batch_lines=" + batch.size() + " parsed=" + parsed);
                    finishBatch(remainingBatches, results, startedAt, ocrFinishedAt,
                            candidateCount, batchCount, callback);
                })
                .addOnFailureListener(error -> {
                    AppLog.error(context, "TRANSLATE", "BATCH_FAILED",
                            "batch_lines=" + batch.size(), error);
                    finishBatch(remainingBatches, results, startedAt, ocrFinishedAt,
                            candidateCount, batchCount, callback);
                });
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

    private void finishBatch(AtomicInteger remainingBatches,
                             List<ScreenTranslation> results,
                             long startedAt, long ocrFinishedAt,
                             int candidateCount, int batchCount,
                             TranslationCallback callback) {
        if (remainingBatches.decrementAndGet() != 0) return;
        complete(results, startedAt, ocrFinishedAt, candidateCount, batchCount, callback);
    }

    private void complete(List<ScreenTranslation> results,
                          long startedAt, long ocrFinishedAt,
                          int candidateCount, int batchCount,
                          TranslationCallback callback) {
        List<ScreenTranslation> ordered = new ArrayList<>(results);
        ordered.sort(Comparator
                .comparingInt((ScreenTranslation item) -> item.y)
                .thenComparingInt(item -> item.x));
        long completedAt = SystemClock.elapsedRealtime();
        AppLog.info(context, "TRANSLATE", "COMPLETED",
                "duration_ms=" + (completedAt - startedAt)
                        + " translation_ms=" + (completedAt - ocrFinishedAt)
                        + " candidates=" + candidateCount
                        + " batches=" + batchCount
                        + " results=" + ordered.size());
        callback.onSuccess(ordered);
    }

    private static Bitmap scaledForOcr(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longEdge = Math.max(width, height);
        if (longEdge <= MAX_OCR_LONG_EDGE) return source;
        float scale = MAX_OCR_LONG_EDGE / (float) longEdge;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
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
