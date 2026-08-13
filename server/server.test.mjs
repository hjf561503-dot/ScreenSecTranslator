import test from "node:test";
import assert from "node:assert/strict";
import {
  buildGeminiRequest,
  buildOpenAIRequest,
  extractGeminiText,
  extractOutputText,
  normalizeTranslationPayload,
  selectProvider
} from "./server.mjs";

test("OpenAI request uses vision, no storage, and strict structured output", () => {
  const request = buildOpenAIRequest("YWJjZA==", 2800, 1752, "gpt-test");
  assert.equal(request.model, "gpt-test");
  assert.equal(request.store, false);
  assert.equal(request.input[0].content[1].type, "input_image");
  assert.equal(request.input[0].content[1].detail, "original");
  assert.equal(request.text.format.type, "json_schema");
  assert.equal(request.text.format.strict, true);
});

test("Gemini request keeps the screenshot inline and requests schema-checked JSON", () => {
  const request = buildGeminiRequest("YWJjZA==", 2800, 1752);
  assert.equal(request.contents[0].parts[1].inlineData.mimeType, "image/jpeg");
  assert.equal(request.contents[0].parts[1].inlineData.data, "YWJjZA==");
  assert.equal(request.generationConfig.responseFormat.text.mimeType, "application/json");
  assert.equal(request.generationConfig.responseFormat.text.schema.type, "object");
});

test("extractOutputText reads Responses API message content", () => {
  const text = extractOutputText({
    output: [{
      type: "message",
      content: [{ type: "output_text", text: '{"translations":[]}' }]
    }]
  });
  assert.equal(text, '{"translations":[]}');
});

test("extractGeminiText reads generateContent candidate text", () => {
  const text = extractGeminiText({
    candidates: [{ content: { parts: [{ text: '{"translations":[]}' }] } }]
  });
  assert.equal(text, '{"translations":[]}');
});

test("auto provider prioritizes Gemini free tier and falls back to OpenAI", () => {
  assert.equal(selectProvider("auto", { gemini: true, openai: true }), "gemini");
  assert.equal(selectProvider("auto", { gemini: false, openai: true }), "openai");
  assert.equal(selectProvider("openai", { gemini: true, openai: true }), "openai");
});

test("normalization clamps model coordinates and removes blank translations", () => {
  const value = normalizeTranslationPayload({
    translations: [
      { source: "Exploit", translated: "漏洞利用", x: -20, y: 950, width: 2000, height: 80 },
      { source: "Blank", translated: "  ", x: 1, y: 1, width: 1, height: 1 }
    ]
  });
  assert.deepEqual(value.translations, [{
    source: "Exploit",
    translated: "漏洞利用",
    x: 0,
    y: 950,
    width: 1000,
    height: 50
  }]);
});
