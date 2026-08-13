package com.yyh.screensectranslator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;

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
import java.util.regex.Pattern;

final class OfflineTranslationEngine implements AutoCloseable {
    interface PrepareCallback {
        void onReady();

        void onFailure(Exception error);
    }

    interface TranslationCallback {
        void onSuccess(PageProgress progress);

        void onFailure(Exception error);
    }

    static final class PageProgress {
        final List<ScreenTranslation> translations;
        final int processedLines;
        final int totalLines;
        final boolean complete;

        PageProgress(List<ScreenTranslation> translations, int processedLines,
                     int totalLines, boolean complete) {
            this.translations = translations;
            this.processedLines = processedLines;
            this.totalLines = totalLines;
            this.complete = complete;
        }
    }

    private static final int MAX_LINES = 100;
    private static final int LINES_PER_PASS = 12;
    private static final int MAX_OCR_LONG_EDGE = 1800;
    private static final int MAX_CACHE_ENTRIES = 800;
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern INTERNAL_MARKER = Pattern.compile(
            "(?i)(?:ZZX|XZZ|QSEG\\d{0,3}|ZX\\d{3}[A-Z]*)");

    private final Context context;
    private final TextRecognizer recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS);
    private final Translator translator;
    private static final Map<String, String> SHARED_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });
    private volatile boolean modelReady;
    private PageSession pageSession;

    OfflineTranslationEngine(Context context) {
        this.context = context.getApplicationContext();
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build();
        translator = Translation.getClient(options);
        CyberGlossary.loadDictionaries(this.context);
    }

    void prepare(PrepareCallback callback) {
        if (modelReady) {
            callback.onReady();
            return;
        }
        OfflineModelState.isDownloaded()
                .addOnSuccessListener(downloaded -> {
                    if (Boolean.TRUE.equals(downloaded)) {
                        modelReady = true;
                        callback.onReady();
                        return;
                    }
                    OfflineModelState.download()
                            .addOnSuccessListener(unused -> {
                                modelReady = true;
                                callback.onReady();
                            })
                            .addOnFailureListener(error ->
                                    callback.onFailure(asException(error)));
                })
                .addOnFailureListener(error -> callback.onFailure(asException(error)));
    }

    synchronized boolean hasPendingPageWork() {
        return pageSession != null && pageSession.nextIndex < pageSession.candidates.size();
    }

    synchronized void clearPage() {
        pageSession = null;
    }

    void translateNewPage(Bitmap bitmap, TranslationCallback callback) {
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
                    PageSession session = createSession(text, ocrWidth, ocrHeight,
                            startedAt, ocrFinishedAt);
                    synchronized (this) {
                        pageSession = session;
                    }
                    processNextPass(session, callback);
                })
                .addOnFailureListener(error -> {
                    if (ocrBitmap != bitmap) ocrBitmap.recycle();
                    AppLog.error(context, "TRANSLATE", "OCR_FAILED",
                            "duration_ms=" + (SystemClock.elapsedRealtime() - startedAt), error);
                    callback.onFailure(asException(error));
                });
    }

    void continuePage(TranslationCallback callback) {
        PageSession session;
        synchronized (this) {
            session = pageSession;
        }
        if (session == null) {
            callback.onFailure(new IllegalStateException("没有可继续处理的静态页面"));
            return;
        }
        processNextPass(session, callback);
    }

    private PageSession createSession(Text recognized, int imageWidth, int imageHeight,
                                      long startedAt, long ocrFinishedAt) {
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
        candidates.sort(Comparator
                .comparingInt((Candidate item) -> item.box.top)
                .thenComparingInt(item -> item.box.left));
        AppLog.info(context, "TRANSLATE", "PAGE_CANDIDATES_READY",
                "candidates=" + candidates.size() + " lines_per_pass=" + LINES_PER_PASS);
        return new PageSession(candidates, imageWidth, imageHeight, startedAt, ocrFinishedAt);
    }

    private void processNextPass(PageSession session, TranslationCallback callback) {
        List<Candidate> pass;
        int passStart;
        int passEnd;
        synchronized (this) {
            if (pageSession != session) {
                callback.onFailure(new IllegalStateException("页面已发生变化"));
                return;
            }
            passStart = session.nextIndex;
            passEnd = Math.min(session.candidates.size(), passStart + LINES_PER_PASS);
            session.nextIndex = passEnd;
            pass = new ArrayList<>(session.candidates.subList(passStart, passEnd));
        }

        if (pass.isEmpty()) {
            callback.onSuccess(snapshot(session));
            return;
        }

        AtomicInteger remaining = new AtomicInteger(pass.size());
        for (Candidate candidate : pass) {
            String exact = CyberGlossary.exactTranslation(candidate.source);
            if (acceptTranslation(session, candidate, exact, "dictionary")) {
                finishLine(session, remaining, callback);
                continue;
            }

            String cached = SHARED_CACHE.get(candidate.source);
            if (acceptTranslation(session, candidate, cached, "cache")) {
                finishLine(session, remaining, callback);
                continue;
            }

            // One OCR line per request. No model-visible separators or placeholder tokens.
            translator.translate(candidate.source)
                    .addOnSuccessListener(raw -> {
                        String translated = CyberGlossary.polishTranslation(candidate.source, raw);
                        if (acceptTranslation(session, candidate, translated, "model")) {
                            SHARED_CACHE.put(candidate.source, translated);
                        } else {
                            AppLog.warn(context, "TRANSLATE", "QUALITY_REJECTED",
                                    "source_chars=" + candidate.source.length()
                                            + " output_chars=" + safeLength(translated));
                        }
                        finishLine(session, remaining, callback);
                    })
                    .addOnFailureListener(error -> {
                        AppLog.error(context, "TRANSLATE", "LINE_FAILED",
                                "source_chars=" + candidate.source.length(), error);
                        finishLine(session, remaining, callback);
                    });
        }
    }

    private boolean acceptTranslation(PageSession session, Candidate candidate,
                                      String translated, String origin) {
        if (!passesQualityGate(candidate.source, translated)) return false;
        ScreenTranslation result = toResult(candidate, translated,
                session.imageWidth, session.imageHeight);
        if (result == null) return false;
        synchronized (this) {
            if (pageSession != session) return false;
            session.results.add(result);
        }
        AppLog.info(context, "TRANSLATE", "LINE_ACCEPTED",
                "origin=" + origin + " source_chars=" + candidate.source.length()
                        + " output_chars=" + translated.length());
        return true;
    }

    private static boolean passesQualityGate(String source, String translated) {
        if (translated == null || translated.trim().isEmpty()) return false;
        String clean = translated.trim();
        if (INTERNAL_MARKER.matcher(clean).find()) return false;
        if (!HAN.matcher(clean).find()) return false;
        if (normalize(source).equals(normalize(clean))) return false;
        return CyberGlossary.protectedTokensPreserved(source, clean);
    }

    private void finishLine(PageSession session, AtomicInteger remaining,
                            TranslationCallback callback) {
        if (remaining.decrementAndGet() != 0) return;
        PageProgress progress = snapshot(session);
        long now = SystemClock.elapsedRealtime();
        AppLog.info(context, "TRANSLATE", "PASS_COMPLETED",
                "duration_ms=" + (now - session.startedAt)
                        + " translation_ms=" + (now - session.ocrFinishedAt)
                        + " processed=" + progress.processedLines
                        + " total=" + progress.totalLines
                        + " results=" + progress.translations.size()
                        + " complete=" + progress.complete);
        callback.onSuccess(progress);
    }

    private synchronized PageProgress snapshot(PageSession session) {
        if (pageSession != session) {
            return new PageProgress(Collections.emptyList(), 0, 0, true);
        }
        List<ScreenTranslation> ordered = new ArrayList<>(session.results);
        ordered.sort(Comparator
                .comparingInt((ScreenTranslation item) -> item.y)
                .thenComparingInt(item -> item.x));
        boolean complete = session.nextIndex >= session.candidates.size();
        return new PageProgress(Collections.unmodifiableList(ordered),
                session.nextIndex, session.candidates.size(), complete);
    }

    private static ScreenTranslation toResult(Candidate candidate, String translated,
                                              int imageWidth, int imageHeight) {
        Rect box = candidate.box;
        return ScreenTranslation.of(
                candidate.source,
                translated,
                box.left * 1000 / Math.max(1, imageWidth),
                box.top * 1000 / Math.max(1, imageHeight),
                Math.max(1, box.width() * 1000 / Math.max(1, imageWidth)),
                Math.max(1, box.height() * 1000 / Math.max(1, imageHeight)));
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

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static Exception asException(Exception error) {
        return error;
    }

    private static final class PageSession {
        final List<Candidate> candidates;
        final List<ScreenTranslation> results = new ArrayList<>();
        final int imageWidth;
        final int imageHeight;
        final long startedAt;
        final long ocrFinishedAt;
        int nextIndex;

        PageSession(List<Candidate> candidates, int imageWidth, int imageHeight,
                    long startedAt, long ocrFinishedAt) {
            this.candidates = candidates;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.startedAt = startedAt;
            this.ocrFinishedAt = ocrFinishedAt;
        }
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
    public synchronized void close() {
        pageSession = null;
        recognizer.close();
        translator.close();
    }
}
