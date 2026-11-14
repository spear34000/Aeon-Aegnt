import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { buildNotWord } from "../src/core/word-chars.ts";

// The Node.js inside the Android app (nodejs-mobile) is built without ICU and cannot parse `\p{L}`: one such regular
// expression anywhere stops the whole server from loading there. These tests keep both forms equal where it matters.

const SAMPLES: [string, string][] = [
  ["안녕하세요, Aeon! 오늘 날씨 어때요? (테스트) 3개 - 끝.", "안녕하세요  Aeon  오늘 날씨 어때요   테스트  3개   끝 "],
  ["Café déjà-vu: 日本語のテキスト、です。", "Café déjà vu  日本語のテキスト です "],
  ["a_b+c=d/e\\f", "a b c d e f"],
];

test("the range version keeps letters and digits of the scripts the app is used in and drops punctuation, whitespace kept", () => {
  const range = buildNotWord(true, false);
  for (const [input, expected] of SAMPLES) assert.equal(input.replace(range, " "), expected);
});

test("where property escapes exist, both versions agree on the samples", () => {
  const props = buildNotWord(true, true);
  const range = buildNotWord(true, false);
  for (const [input] of SAMPLES) assert.equal(input.replace(range, " "), input.replace(props, " "));
});

test("the version without whitespace also removes spaces", () => {
  for (const withProperties of [true, false]) {
    assert.equal("한 글, abc 12!".replace(buildNotWord(false, withProperties), ""), "한글abc12");
  }
});

test("no source file outside core/word-chars.ts uses a Unicode property escape", () => {
  const found: string[] = [];
  const walk = (dir: string): void => {
    for (const name of readdirSync(dir)) {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) walk(path);
      else if (name.endsWith(".ts") && !path.endsWith(join("core", "word-chars.ts")) && /\\[pP]\{/.test(readFileSync(path, "utf8"))) found.push(path);
    }
  };
  walk(join(import.meta.dirname, "..", "src"));
  assert.deepEqual(found, [], "use NOT_WORD / NOT_WORD_OR_SPACE from core/word-chars.ts instead");
});
