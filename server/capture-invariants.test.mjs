import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const sourceUrl = new URL(
  "../app/src/main/java/com/yyh/screensectranslator/ScreenTranslateService.java",
  import.meta.url
);
const logSourceUrl = new URL(
  "../app/src/main/java/com/yyh/screensectranslator/AppLog.java",
  import.meta.url
);
const activitySourceUrl = new URL(
  "../app/src/main/java/com/yyh/screensectranslator/MainActivity.java",
  import.meta.url
);
const engineSourceUrl = new URL(
  "../app/src/main/java/com/yyh/screensectranslator/OfflineTranslationEngine.java",
  import.meta.url
);
const glossarySourceUrl = new URL(
  "../app/src/main/java/com/yyh/screensectranslator/CyberGlossary.java",
  import.meta.url
);
const dictionaryUrl = new URL(
  "../app/src/main/assets/cyber-security-en-zh.tsv",
  import.meta.url
);

test("capture resize callback ignores unchanged dimensions before rebuilding", async () => {
  const source = await readFile(sourceUrl, "utf8");
  const callbackStart = source.indexOf("void onCapturedContentResize(int width, int height)");
  const callbackEnd = source.indexOf("\n        }\n    };", callbackStart);
  assert.ok(callbackStart >= 0 && callbackEnd > callbackStart);
  const callback = source.slice(callbackStart, callbackEnd);
  const unchangedGuard = callback.indexOf(
    "width == captureWidth && height == captureHeight && density == captureDensity"
  );
  const rebuildCall = callback.indexOf("resizeCaptureSurface(width, height, density)");
  assert.ok(unchangedGuard >= 0, "unchanged-size guard must exist");
  assert.ok(rebuildCall > unchangedGuard, "guard must run before surface rebuild");
});

test("capture never hides the bubble or enables FLAG_SECURE", async () => {
  const source = await readFile(sourceUrl, "utf8");
  const hideStart = source.indexOf("private void hideOverlayForCapture()");
  const hideEnd = source.indexOf("private void restoreOverlayAfterCapture()", hideStart);
  assert.ok(hideStart >= 0 && hideEnd > hideStart);
  const hideMethod = source.slice(hideStart, hideEnd);
  assert.doesNotMatch(hideMethod, /bubbleView\.setVisibility/);
  assert.doesNotMatch(source, /WindowManager\.LayoutParams\.FLAG_SECURE/);
});

test("bubble is never removed and re-added to change overlay z-order", async () => {
  const source = await readFile(sourceUrl, "utf8");
  assert.doesNotMatch(source, /bringBubbleToFront/);
  const removals = source.match(/removeView\(bubbleView\)/g) || [];
  assert.equal(removals.length, 1, "bubble may only be removed during service shutdown");
  const shutdown = source.indexOf("public void onDestroy()");
  const removal = source.indexOf("removeView(bubbleView)");
  assert.ok(shutdown >= 0 && removal > shutdown, "the only bubble removal must be in onDestroy");
});

test("overlay creation failure is contained without killing the bubble service", async () => {
  const source = await readFile(sourceUrl, "utf8");
  assert.match(source, /catch \(RuntimeException error\)/);
  assert.match(source, /UPDATE_FAILED_KEEPING_BUBBLE/);
  assert.match(source, /译文覆盖层创建失败；悬浮球仍会保留/);
  assert.match(source, /"BUBBLE", "TAPPED"/);
});

test("log version comes from the installed package without generated BuildConfig", async () => {
  const source = await readFile(logSourceUrl, "utf8");
  assert.match(source, /getPackageInfo\(context\.getPackageName\(\), 0\)\.versionName/);
  assert.doesNotMatch(source, /BuildConfig/);
});

test("startup checks the real ML Kit model state before requesting capture", async () => {
  const activity = await readFile(activitySourceUrl, "utf8");
  const engine = await readFile(engineSourceUrl, "utf8");
  assert.match(activity, /OfflineModelState\.isDownloaded\(\)/);
  assert.match(activity, /离线模型已验证/);
  assert.doesNotMatch(activity, /已启动：模型就绪后/);
  assert.match(engine, /OfflineModelState\.isDownloaded\(\)/);
  assert.doesNotMatch(engine, /downloadModelIfNeeded/);
});

test("free terminology protects security tools and CamelCase product names", async () => {
  const glossary = await readFile(glossarySourceUrl, "utf8");
  const dictionary = await readFile(dictionaryUrl, "utf8");
  assert.match(dictionary, /^dirb\t@KEEP$/m);
  assert.match(dictionary, /^dirbuster\t@KEEP$/m);
  assert.match(dictionary, /^hidden page finder\t隐藏页面发现工具$/m);
  assert.match(glossary, /CAMEL_CASE/);
  assert.match(glossary, /externalKeep/);
});

test("offline translation never exposes model-visible placeholder delimiters", async () => {
  const engine = await readFile(engineSourceUrl, "utf8");
  const glossary = await readFile(glossarySourceUrl, "utf8");
  assert.doesNotMatch(engine, /CyberGlossary\.protect\(|QSEG%|BATCH_SEGMENT/);
  assert.doesNotMatch(glossary, /"ZZX"|class ProtectedText|class Replacement/);
  assert.match(engine, /translator\.translate\(candidate\.source\)/);
});

test("static pages are completed progressively without repeating OCR", async () => {
  const service = await readFile(sourceUrl, "utf8");
  const engine = await readFile(engineSourceUrl, "utf8");
  assert.match(engine, /LINES_PER_PASS/);
  assert.match(engine, /class PageSession/);
  assert.match(engine, /boolean hasPendingPageWork\(\)/);
  assert.match(engine, /void continuePage\(TranslationCallback callback\)/);
  const pendingCheck = service.indexOf("offlineEngine.hasPendingPageWork()");
  const completedSkip = service.indexOf('"UNCHANGED_PAGE_COMPLETE"');
  assert.ok(pendingCheck >= 0, "unchanged pages must check pending translation work");
  assert.ok(completedSkip > pendingCheck, "pending work must continue before skip logic");
  assert.match(service, /offlineEngine\.continuePage/);
});

test("quality gate rejects internal markers and untranslated English", async () => {
  const engine = await readFile(engineSourceUrl, "utf8");
  assert.match(engine, /INTERNAL_MARKER/);
  assert.match(engine, /HAN\.matcher\(clean\)\.find\(\)/);
  assert.match(engine, /CyberGlossary\.protectedTokensPreserved/);
  assert.match(engine, /QUALITY_REJECTED/);
});

test("dictionary covers the security dashboard fixture", async () => {
  const dictionary = await readFile(dictionaryUrl, "utf8");
  assert.match(dictionary, /^security analyst dashboard\t安全分析师仪表盘$/m);
  assert.match(dictionary, /^sql injection attack\tSQL 注入攻击$/m);
  assert.match(dictionary, /^enumeration attempt\t枚举尝试$/m);
  assert.match(dictionary, /^event tracking\t事件跟踪$/m);
});
