import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const sourceUrl = new URL(
  "../app/src/main/java/com/yyh/screensectranslator/ScreenTranslateService.java",
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
