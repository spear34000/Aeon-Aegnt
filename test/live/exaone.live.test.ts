// LIVE test against the real EXAONE API Platform. SKIPPED unless the owner opts in:
//   set EXAONE_API_KEY=... and AEON_LIVE=1, then:  npm run test:live
// This sends one tiny prompt ("Reply with OK") using the owner's own key and quota.
// Results here are the only evidence of real-service verification; everything else in test/ is mocked.
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { exaoneProvider } from "../../src/core/models/providers.ts";

const enabled = process.env.AEON_LIVE === "1" && !!process.env.EXAONE_API_KEY;

describe("EXAONE live (real network)", { skip: enabled ? false : "set AEON_LIVE=1 and EXAONE_API_KEY to run" }, () => {
  it("answers a trivial prompt using k-exaone_v2.5", async () => {
    const p = exaoneProvider({ apiKey: process.env.EXAONE_API_KEY!, reasoningEffort: "low", timeoutMs: 120_000 });
    const r = await p.chat({ messages: [{ role: "user", content: "Reply with the single word OK." }], maxTokens: 2048 });
    assert.ok(r.content.length > 0);
    assert.ok(r.usage.inputTokens > 0);
    console.log(`live ok: ${JSON.stringify({ content: r.content.slice(0, 40), usage: r.usage })}`);
  });
});
