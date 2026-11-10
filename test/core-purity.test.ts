import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? walk(p) : p.endsWith(".ts") ? [p] : [];
  });
}

/**
 * Decision D1/D2: the core must be able to run on Cloudflare Workers, so it may not use
 * Node-only APIs. This is a static guard; real Workers execution is verified in Phase 2.
 */
describe("src/core stays runtime-neutral (Workers-compatible APIs only)", () => {
  const coreDir = join(import.meta.dirname, "..", "src", "core");
  const forbidden: [RegExp, string][] = [
    [/from\s+["']node:/, "node: import"],
    [/from\s+["'](fs|path|os|child_process|net|http|https|crypto|stream|util|events)["']/, "bare Node builtin import"],
    [/\brequire\s*\(/, "require()"],
    [/\bprocess\./, "process global"],
    [/\bBuffer\b/, "Buffer"],
    [/__dirname|__filename/, "CJS globals"],
    [/\.\.\/node\//, "import from src/node"],
  ];
  for (const file of walk(coreDir)) {
    it(`${file.slice(coreDir.length + 1)} has no Node-only dependencies`, () => {
      const src = readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
      for (const [re, what] of forbidden) assert.ok(!re.test(src), `uses ${what}`);
    });
  }
});
