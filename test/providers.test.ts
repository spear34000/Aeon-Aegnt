import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ManualClock } from "../src/core/clock.ts";
import { ModelError } from "../src/core/errors.ts";
import { MemoryStore } from "../src/core/memory-store.ts";
import { MockProvider } from "../src/core/models/mock.ts";
import { OpenAICompatibleProvider, parseRetryAfter } from "../src/core/models/openai-compatible.ts";
import { exaoneProvider, ollamaProvider } from "../src/core/models/providers.ts";
import { ModelRouter, QuotaWaitError } from "../src/core/models/router.ts";
import { Redactor } from "../src/core/redact.ts";
import { createRuntime } from "../src/node/runtime.ts";
import { tempDir } from "./helpers.ts";

const KEY = "exa-key-1234567890-SECRET";

interface Seen {
  url: string;
  init: RequestInit;
}
function fakeFetch(respond: () => Response | Promise<Response>, seen: Seen[] = []): typeof fetch {
  return (async (url: string | URL, init?: RequestInit) => {
    seen.push({ url: String(url), init: init ?? {} });
    return respond();
  }) as typeof fetch;
}
const okBody = (content = "OK", extra: Record<string, unknown> = {}) =>
  new Response(
    JSON.stringify({
      choices: [{ message: { content, reasoning_content: "thinking...", tool_calls: [] } }],
      usage: { prompt_tokens: 11, completion_tokens: 7, reasoning_tokens: 5 },
      ...extra,
    }),
    { status: 200, headers: { "content-type": "application/json" } },
  );

describe("EXAONE provider (request/response contract, MOCKED transport)", () => {
  it("calls the documented endpoint with Bearer auth, documented model id, and parses reasoning separately", async () => {
    const seen: Seen[] = [];
    const p = exaoneProvider({ apiKey: KEY, reasoningEffort: "low", fetch: fakeFetch(() => okBody("hello"), seen) });
    const r = await p.chat({ messages: [{ role: "user", content: "Hello" }], maxTokens: 512 });

    assert.equal(seen[0]!.url, "https://api.exaone.ai/v1/chat/completions");
    const headers = seen[0]!.init.headers as Record<string, string>;
    assert.equal(headers["authorization"], `Bearer ${KEY}`);
    const body = JSON.parse(String(seen[0]!.init.body));
    assert.equal(body.model, "k-exaone_v2.5");
    assert.equal(body.stream, false);
    assert.equal(body.max_tokens, 512);
    assert.equal(body.reasoning_effort, "low");

    assert.equal(r.content, "hello");
    assert.equal(r.reasoning, "thinking...", "reasoning kept apart from the answer");
    assert.deepEqual(r.usage, { inputTokens: 11, outputTokens: 7, reasoningTokens: 5 });
  });

  it("defaults reasoning_effort to low (API default is max and reasoning counts toward max_tokens)", async () => {
    const seen: Seen[] = [];
    const p = exaoneProvider({ apiKey: KEY, fetch: fakeFetch(() => okBody(), seen) });
    await p.chat({ messages: [] });
    assert.equal(JSON.parse(String(seen[0]!.init.body)).reasoning_effort, "low");
  });

  it("reports empty / length-cut answers as 'truncated', not as a format error", async () => {
    const cut = () =>
      new Response(JSON.stringify({ choices: [{ finish_reason: "length", message: { content: "", reasoning_content: "long thoughts" } }] }), { status: 200 });
    const p = exaoneProvider({ apiKey: KEY, fetch: fakeFetch(cut) });
    await assert.rejects(p.chat({ messages: [] }), (e: unknown) => e instanceof ModelError && e.kind === "truncated" && !e.retryable);
  });

  it("does not assume undocumented capabilities and is cleared for public release (decision D9)", () => {
    const p = exaoneProvider({ apiKey: KEY });
    const c = p.capabilities();
    assert.equal(c.modelId, "k-exaone_v2.5");
    assert.equal(c.contextWindow, 524_288);
    assert.equal(c.toolCalling, "unknown");
    assert.equal(c.structuredOutput, "unknown");
    assert.equal(c.requestsPerMinute, null, "limits are not published, so not invented");
    assert.equal(p.unreleased, false);
    assert.equal(p.external, true);
  });

  it("release mode refuses any provider flagged unreleased (mechanism kept for future integrations)", () => {
    const { dir, cleanup } = tempDir();
    class Draft extends MockProvider {
      override readonly unreleased = true as never;
    }
    const base = {
      workspace: dir,
      providers: ["draft"],
      fallbackAllowed: false,
      dataConsent: [] as string[],
      providerOverrides: [new Draft("draft", [])],
      store: new MemoryStore(),
    };
    assert.throws(() => createRuntime({ ...base, releaseMode: true }), /unreleased providers: draft/);
    assert.ok(createRuntime(base).router, "developer mode still allows it");
    cleanup();
  });

  it("requires the key from the environment instead of reading it from anywhere else", () => {
    const { dir, cleanup } = tempDir();
    assert.throws(
      () => createRuntime({ workspace: dir, providers: ["exaone"], fallbackAllowed: false, dataConsent: [], env: {}, store: new MemoryStore() }),
      /EXAONE_API_KEY/,
    );
    cleanup();
  });
});

describe("OpenAI-compatible error mapping and secret hygiene (MOCKED transport)", () => {
  const mk = (respond: () => Response | Promise<Response>, redactor = new Redactor()) =>
    exaoneProvider({ apiKey: KEY, fetch: fakeFetch(respond), redactor });
  const expectKind = async (p: OpenAICompatibleProvider, kind: string) => {
    await assert.rejects(p.chat({ messages: [] }), (e: unknown) => e instanceof ModelError && e.kind === kind);
  };

  it("maps documented HTTP statuses to normalised kinds", async () => {
    await expectKind(mk(() => new Response("no", { status: 401 })), "auth");
    await expectKind(mk(() => new Response("no", { status: 400 })), "bad_request");
    await expectKind(mk(() => new Response("no", { status: 422 })), "bad_request");
    await expectKind(mk(() => new Response("no", { status: 404 })), "bad_request");
    await expectKind(mk(() => new Response("no", { status: 500 })), "server");
    await expectKind(mk(() => new Response("no", { status: 429 })), "rate_limited");
  });

  it("429 with day window exhausted => quota_exhausted; retry-after headers are honoured", async () => {
    const p = mk(() => new Response("limit", { status: 429, headers: { "x-day-rate-limit-remaining": "0", "retry-after": "120" } }));
    await assert.rejects(p.chat({ messages: [] }), (e: unknown) => {
      assert.ok(e instanceof ModelError);
      assert.equal(e.kind, "quota_exhausted");
      assert.equal(e.retryAfterMs, 120_000);
      return true;
    });
    const p2 = mk(() => new Response("limit", { status: 429, headers: { "retry-after-ms": "1500", "retry-after": "99" } }));
    await assert.rejects(p2.chat({ messages: [] }), (e: unknown) => (e as ModelError).retryAfterMs === 1500);
  });

  it("network failures and invalid payloads are normalised", async () => {
    await expectKind(
      mk(() => {
        throw new TypeError("fetch failed");
      }),
      "network",
    );
    await expectKind(mk(() => new Response("<html>", { status: 200 })), "invalid_response");
    await expectKind(mk(() => new Response(JSON.stringify({ choices: [] }), { status: 200 })), "invalid_response");
  });

  it("never leaks the API key into error messages, even if the server echoes it back", async () => {
    const p = mk(() => new Response(`invalid key ${KEY} for Bearer ${KEY}`, { status: 401 }));
    await assert.rejects(p.chat({ messages: [] }), (e: unknown) => {
      assert.ok(e instanceof ModelError);
      assert.ok(!e.message.includes(KEY), e.message);
      return true;
    });
    const p2 = mk(() => {
      throw new TypeError(`connect failed for key ${KEY}`);
    });
    await assert.rejects(p2.chat({ messages: [] }), (e: unknown) => !(e as Error).message.includes(KEY));
  });

  it("parseRetryAfter handles seconds, ms, dates and junk", () => {
    assert.equal(parseRetryAfter(new Headers({ "retry-after": "2" })), 2000);
    assert.equal(parseRetryAfter(new Headers({ "retry-after-ms": "250" })), 250);
    assert.equal(parseRetryAfter(new Headers({ "retry-after": "garbage" })), undefined);
    assert.equal(parseRetryAfter(new Headers()), undefined);
  });

  it("Ollama preset is local (no auth header, not external)", async () => {
    const seen: Seen[] = [];
    const p = ollamaProvider({ model: "exaone3.5:7.8b", fetch: fakeFetch(() => okBody(), seen) });
    await p.chat({ messages: [{ role: "user", content: "hi" }] });
    assert.equal(seen[0]!.url, "http://localhost:11434/v1/chat/completions");
    assert.equal((seen[0]!.init.headers as Record<string, string>)["authorization"], undefined);
    assert.equal(p.external, false);
  });
});

describe("ModelRouter: consent, fallback, quotas", () => {
  const rateLimited = (id: string, retryAfterMs?: number) =>
    new ModelError("rate_limited", id, "429", retryAfterMs !== undefined ? { retryAfterMs } : {});
  const mkRouter = (providers: MockProvider[], o: { fallback?: boolean; consent?: string[]; limits?: Record<string, { maxCallsPerDay?: number }> } = {}) => {
    const clock = new ManualClock();
    const store = new MemoryStore();
    return {
      clock,
      store,
      router: new ModelRouter({
        providers,
        fallbackAllowed: o.fallback ?? false,
        dataConsent: new Set(o.consent ?? providers.map((p) => p.id)),
        ...(o.limits ? { limits: o.limits } : {}),
        store,
        clock,
      }),
    };
  };

  it("never sends prompts to an external provider the owner has not consented to", async () => {
    const ext = new MockProvider("ext", ["x"], { external: true });
    const { router } = mkRouter([ext], { consent: [] });
    await assert.rejects(router.chat({ messages: [] }, { taskId: null }), /no model provider is permitted/);
    assert.equal(ext.calls.length, 0);
  });

  it("without owner-approved fallback, only the primary is ever tried", async () => {
    const a = new MockProvider("a", [new ModelError("server", "a", "down")]);
    const b = new MockProvider("b", ["from b"]);
    const { router } = mkRouter([a, b], { fallback: false });
    await assert.rejects(router.chat({ messages: [] }, { taskId: null }), ModelError);
    assert.equal(b.calls.length, 0);
  });

  it("with approved fallback, switches to the next consented provider on quota/outage", async () => {
    const a = new MockProvider("a", [rateLimited("a", 60_000)], { external: true });
    const b = new MockProvider("b", ["from b"]);
    const { router, store } = mkRouter([a, b], { fallback: true });
    const r = await router.chat({ messages: [] }, { taskId: "t" });
    assert.equal(r.content, "from b");
    assert.equal(r.provider, "b");
    const usage = await store.listUsage();
    assert.deepEqual(usage.map((u) => [u.provider, u.ok]), [["a", false], ["b", true]]);
  });

  it("fallback does not skip over a consent boundary", async () => {
    const a = new MockProvider("a", [rateLimited("a", 60_000)]);
    const b = new MockProvider("b", ["leak"], { external: true });
    const { router } = mkRouter([a, b], { fallback: true, consent: ["a"] });
    await assert.rejects(router.chat({ messages: [] }, { taskId: null }), QuotaWaitError);
    assert.equal(b.calls.length, 0);
  });

  it("when every permitted provider is rate limited, raises QuotaWaitError with the earliest reset", async () => {
    const a = new MockProvider("a", [rateLimited("a", 30_000)]);
    const { router, clock } = mkRouter([a]);
    await assert.rejects(router.chat({ messages: [] }, { taskId: null }), (e: unknown) => {
      assert.ok(e instanceof QuotaWaitError);
      assert.equal(e.until, clock.now() + 30_000);
      return true;
    });
    // While blocked, the provider is not hammered again.
    await assert.rejects(router.chat({ messages: [] }, { taskId: null }), QuotaWaitError);
    assert.equal(a.calls.length, 1);
    clock.advance(30_000);
    a.push("back");
    assert.equal((await router.chat({ messages: [] }, { taskId: null })).content, "back");
  });

  it("does not fail over on bad_request (do not shop a prompt around)", async () => {
    const a = new MockProvider("a", [new ModelError("bad_request", "a", "422")]);
    const b = new MockProvider("b", ["x"]);
    const { router } = mkRouter([a, b], { fallback: true });
    await assert.rejects(router.chat({ messages: [] }, { taskId: null }), (e: unknown) => (e as ModelError).kind === "bad_request");
    assert.equal(b.calls.length, 0);
  });

  it("enforces the owner's local daily call budget before the provider blocks us", async () => {
    const a = new MockProvider("a", ["1", "2", "3"]);
    const { router, clock } = mkRouter([a], { limits: { a: { maxCallsPerDay: 2 } } });
    await router.chat({ messages: [] }, { taskId: null });
    await router.chat({ messages: [] }, { taskId: null });
    await assert.rejects(router.chat({ messages: [] }, { taskId: null }), QuotaWaitError);
    assert.equal(a.calls.length, 2, "third call never reached the provider");
    clock.advance(86_400_000);
    assert.equal((await router.chat({ messages: [] }, { taskId: null })).content, "3");
  });
});
