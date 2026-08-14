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
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

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

    private static final int LINES_PER_PASS = 12;
    private static final String WARM_UP_TEXT = "Network security.";
    // Keep the Samsung Tab S9+ native 2800 px screenshot intact. Scaling it to
    // 1800 px made small dashboard labels fall below ML Kit's useful character size.
    private static final int MAX_OCR_LONG_EDGE = 3200;
    private static final int MAX_CACHE_ENTRIES = 800;
    private final Context context;
    private final TextRecognizer recognizer = TextRecognition.getClient(
            new ChineseTextRecognizerOptions.Builder().build());
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
                        warmUpTranslator(callback);
                        return;
                    }
                    OfflineModelState.download()
                            .addOnSuccessListener(unused -> warmUpTranslator(callback))
                            .addOnFailureListener(error ->
                                    callback.onFailure(asException(error)));
                })
                .addOnFailureListener(error -> callback.onFailure(asException(error)));
    }

    private void warmUpTranslator(PrepareCallback callback) {
        long startedAt = SystemClock.elapsedRealtime();
        AppLog.info(context, "MODEL", "RUNTIME_WARMUP_STARTED", "offline=true");
        translator.translate(WARM_UP_TEXT)
                .addOnSuccessListener(output -> {
                    if (output == null || output.trim().isEmpty()
                            || !CyberGlossary.containsHan(output)) {
                        callback.onFailure(new IllegalStateException(
                                "离线模型预热未返回中文"));
                        return;
                    }
                    modelReady = true;
                    AppLog.info(context, "MODEL", "RUNTIME_WARMUP_COMPLETED",
                            "duration_ms=" + (SystemClock.elapsedRealtime() - startedAt));
                    callback.onReady();
                })
                .addOnFailureListener(error -> {
                    AppLog.error(context, "MODEL", "RUNTIME_WARMUP_FAILED",
                            "duration_ms=" + (SystemClock.elapsedRealtime() - startedAt), error);
                    callback.onFailure(asException(error));
                });
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
        List<Candidate> rawCandidates = new ArrayList<>();
        for (Text.TextBlock block : recognized.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                addLineCandidates(line, rawCandidates);
            }
        }
        List<Candidate> candidates = deduplicateCandidates(rawCandidates);
        candidates.sort(Comparator
                .comparingInt((Candidate item) -> item.box.top)
                .thenComparingInt(item -> item.box.left));
        AppLog.info(context, "TRANSLATE", "PAGE_CANDIDATES_READY",
                "raw_candidates=" + rawCandidates.size()
                        + " candidates=" + candidates.size()
                        + " duplicates_removed=" + (rawCandidates.size() - candidates.size())
                        + " lines_per_pass=" + LINES_PER_PASS);
        return new PageSession(candidates, imageWidth, imageHeight, startedAt, ocrFinishedAt);
    }

    private static List<Candidate> deduplicateCandidates(List<Candidate> candidates) {
        List<Candidate> unique = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String candidateKey = normalizedSource(candidate.source);
            int duplicateIndex = -1;
            for (int i = 0; i < unique.size(); i++) {
                Candidate existing = unique.get(i);
                if (candidateKey.equals(normalizedSource(existing.source))
                        && overlapOverSmaller(candidate.box, existing.box) >= 0.68f) {
                    duplicateIndex = i;
                    break;
                }
            }
            if (duplicateIndex < 0) {
                unique.add(candidate);
                continue;
            }
            Candidate existing = unique.get(duplicateIndex);
            if (area(candidate.box) < area(existing.box)) {
                unique.set(duplicateIndex, candidate);
            }
        }
        return unique;
    }

    private static String normalizedSource(String value) {
        return clean(value).toLowerCase(java.util.Locale.ROOT);
    }

    private static float overlapOverSmaller(Rect first, Rect second) {
        int left = Math.max(first.left, second.left);
        int top = Math.max(first.top, second.top);
        int right = Math.min(first.right, second.right);
        int bottom = Math.min(first.bottom, second.bottom);
        if (right <= left || bottom <= top) return 0f;
        long intersection = (long) (right - left) * (bottom - top);
        return intersection / (float) Math.max(1L, Math.min(area(first), area(second)));
    }

    private static long area(Rect box) {
        return (long) Math.max(0, box.width()) * Math.max(0, box.height());
    }

    private static void addLineCandidates(Text.Line line, List<Candidate> candidates) {
        String fullText = clean(line.getText());
        Rect fullBox = line.getBoundingBox();
        if (fullBox == null || fullText.isEmpty()) return;
        if (!CyberGlossary.containsHan(fullText)) {
            if (CyberGlossary.shouldTranslate(fullText)) {
                candidates.add(new Candidate(fullText, fullBox));
            }
            return;
        }

        RunBuilder run = new RunBuilder();
        for (Text.Element element : line.getElements()) {
            String text = clean(element.getText());
            Rect box = element.getBoundingBox();
            if (text.isEmpty() || box == null) {
                flushRun(run, candidates);
                continue;
            }
            if (CyberGlossary.containsHan(text)) {
                flushRun(run, candidates);
                addEnglishSymbolsFromMixedElement(element, candidates);
                continue;
            }
            run.append(text, box);
        }
        flushRun(run, candidates);
    }

    private static void addEnglishSymbolsFromMixedElement(
            Text.Element element, List<Candidate> candidates) {
        RunBuilder symbols = new RunBuilder();
        for (Text.Symbol symbol : element.getSymbols()) {
            String value = clean(symbol.getText());
            Rect box = symbol.getBoundingBox();
            if (value.isEmpty() || box == null || CyberGlossary.containsHan(value)) {
                flushRun(symbols, candidates);
                continue;
            }
            symbols.appendContiguous(value, box);
        }
        flushRun(symbols, candidates);
    }

    private static void flushRun(RunBuilder run, List<Candidate> candidates) {
        if (run.isEmpty()) return;
        String source = run.source();
        Rect box = run.box();
        if (!CyberGlossary.containsHan(source) && CyberGlossary.shouldTranslate(source)) {
            candidates.add(new Candidate(source, box));
        }
        run.clear();
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
                        String translated = CyberGlossary.sanitizeTranslation(candidate.source, raw);
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
        String clean = CyberGlossary.sanitizeTranslation(candidate.source, translated);
        if (clean == null) return false;
        ScreenTranslation result = toResult(candidate, clean,
                session.imageWidth, session.imageHeight);
        if (result == null) return false;
        synchronized (this) {
            if (pageSession != session) return false;
            session.results.add(result);
        }
        AppLog.info(context, "TRANSLATE", "LINE_ACCEPTED",
                "origin=" + origin + " source_chars=" + candidate.source.length()
                        + " output_chars=" + clean.length());
        return true;
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

    private static final class RunBuilder {
        private final StringBuilder text = new StringBuilder();
        private Rect bounds;
        private Rect previousBox;
        private float previousGlyphWidth;

        void append(String value, Rect box) {
            append(value, box, true);
        }

        void appendContiguous(String value, Rect box) {
            append(value, box, false);
        }

        private void append(String value, Rect box, boolean inferWordSpace) {
            if (value == null || value.isEmpty()) return;
            float glyphWidth = box.width()
                    / (float) Math.max(1, value.codePointCount(0, value.length()));
            boolean visualWordGap = !inferWordSpace && previousBox != null
                    && box.left > previousBox.right
                    && box.left - previousBox.right
                    > Math.max(1f, Math.min(previousGlyphWidth, glyphWidth) * 0.45f);
            if (text.length() > 0
                    && (inferWordSpace || visualWordGap)
                    && needsSpace(text.charAt(text.length() - 1), value.charAt(0))) {
                text.append(' ');
            }
            text.append(value);
            if (bounds == null) bounds = new Rect(box);
            else bounds.union(box);
            previousBox = new Rect(box);
            previousGlyphWidth = Math.max(1f, glyphWidth);
        }

        boolean isEmpty() {
            return text.length() == 0 || bounds == null;
        }

        String source() {
            return clean(text.toString());
        }

        Rect box() {
            return new Rect(bounds);
        }

        void clear() {
            text.setLength(0);
            bounds = null;
            previousBox = null;
            previousGlyphWidth = 0f;
        }

        private static boolean needsSpace(char left, char right) {
            if (Character.isWhitespace(left) || Character.isWhitespace(right)) return false;
            String noSpaceBefore = ",.!?;:%)]}，。！？；：、";
            String noSpaceAfter = "([{“‘";
            return noSpaceBefore.indexOf(right) < 0 && noSpaceAfter.indexOf(left) < 0;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    @Override
    public synchronized void close() {
        pageSession = null;
        recognizer.close();
        translator.close();
    }
}
