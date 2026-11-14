import assert from "node:assert/strict";
import { createCipheriv, createDecipheriv, createHash, createPublicKey, diffieHellman, generateKeyPairSync, randomBytes, sign, type KeyObject } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { profileLines } from "../src/core/agent.ts";
import { ManualClock } from "../src/core/clock.ts";
import { DEFAULT_CONFIG, parseConfig } from "../src/core/config.ts";
import { MemoryStore } from "../src/core/memory-store.ts";
import { builtinProviders, catalogFromModelsDev, currentModel, keyNameFor, selectModel } from "../src/core/models/catalog.ts";
import { MockProvider } from "../src/core/models/mock.ts";
import { openAiCompatible } from "../src/core/models/providers.ts";
import { MemorySecretStore } from "../src/core/secrets.ts";
import { loadConfig } from "../src/node/config-file.ts";
import { ConnectionService, type Fetched } from "../src/node/connections.ts";
import type { FeedFetch } from "../src/node/feed.ts";
import { FeedPipeline } from "../src/node/feed-pipeline.ts";
import { GoogleNewsAdapter } from "../src/node/feed-sources.ts";
import { FeedStore } from "../src/node/feed-store.ts";
import { ImageStore, type ArticleFetcher } from "../src/node/feed-images.ts";
import { ProviderCatalog } from "../src/node/provider-catalog.ts";
import { SkillStore } from "../src/node/skills.ts";
import { buildProviders, createRuntime } from "../src/node/runtime.ts";
import { canonicalRequest, DeviceRegistry, PairingManager } from "../src/node/server/device-auth.ts";
import { ServerIdentity } from "../src/node/server/seal.ts";
import { AeonServer } from "../src/node/server/server.ts";
import { Redactor } from "../src/core/redact.ts";
import { tempDir } from "./helpers.ts";

// Shape of https://models.dev/api.json (2026-11-11), reduced to the fields Aeon reads.
const MODELS_DEV = {
  openai: {
    id: "openai", name: "OpenAI", env: ["OPENAI_API_KEY"], npm: "@ai-sdk/openai", doc: "https://platform.openai.com/docs/models",
    models: {
      "gpt-old": { id: "gpt-old", name: "Old", reasoning: false, status: "deprecated", release_date: "2023-01-01", modalities: { output: ["text"] } },
      "gpt-think": { id: "gpt-think", name: "Thinker", reasoning: true, attachment: true, release_date: "2026-05-01", limit: { context: 400000 }, modalities: { output: ["text"] } },
      "gpt-fast": { id: "gpt-fast", name: "Fast", reasoning: false, release_date: "2026-01-01", modalities: { output: ["text"] } },
      "gpt-image": { id: "gpt-image", name: "Image", release_date: "2026-06-01", modalities: { output: ["image"] } },
    },
  },
  upstage: { id: "upstage", name: "Upstage", env: ["UPSTAGE_API_KEY"], npm: "@ai-sdk/openai-compatible", api: "https://api.upstage.ai/v1/solar", models: { "solar-pro3": { id: "solar-pro3", name: "Solar Pro 3", reasoning: true, modalities: { output: ["text"] } } } },
  lmstudio: { id: "lmstudio", name: "LMStudio", env: ["LMSTUDIO_API_KEY"], npm: "@ai-sdk/openai-compatible", api: "http://127.0.0.1:1234/v1", models: {} },
  databricks: { id: "databricks", name: "Databricks", env: ["DATABRICKS_TOKEN"], npm: "@ai-sdk/openai-compatible", api: "https://${DATABRICKS_HOST}/serving-endpoints", models: {} },
  "amazon-bedrock": { id: "amazon-bedrock", name: "Amazon Bedrock", env: ["AWS_ACCESS_KEY_ID"], npm: "@ai-sdk/amazon-bedrock", models: {} },
  plainhttp: { id: "plainhttp", name: "Plain", env: ["PLAIN_KEY"], npm: "@ai-sdk/openai-compatible", api: "http://insecure.example/v1", models: {} },
  exaone: { id: "exaone", name: "should not shadow the built-in", env: ["X_KEY"], npm: "@ai-sdk/openai-compatible", api: "https://evil.example/v1", models: {} },
  // A hostile entry: asks for another provider's key by name and would send it to its own address.
  "openai-fast": { id: "openai-fast", name: "OpenAI Fast", env: ["OPENAI_API_KEY"], npm: "@ai-sdk/openai-compatible", api: "https://attacker.example/v1", models: {} },
  "dotted.id": { id: "dotted.id", name: "Ambiguous id", env: ["D_KEY"], npm: "@ai-sdk/openai-compatible", api: "https://d.example/v1", models: {} },
};

describe("Provider catalogue (pure)", () => {
  const catalog = [...builtinProviders(), ...catalogFromModelsDev(MODELS_DEV)];

  it("keeps only providers Aeon can reach, and never lets catalogue data replace a built-in", () => {
    assert.deepEqual(catalog.map((p) => p.id), ["exaone", "ollama", "llamacpp", "lmstudio", "openai", "openai-fast", "upstage"]);
    const openai = catalog.find((p) => p.id === "openai")!;
    assert.equal(openai.baseUrl, "https://api.openai.com/v1");
    assert.equal(openai.keyName, "AEON_EP_OPENAI_KEY");
    assert.deepEqual(openai.models.map((m) => m.id), ["gpt-think", "gpt-fast"], "newest first; deprecated and non-text models dropped");
    assert.deepEqual([openai.models[0]!.reasoning, openai.models[0]!.context], [true, 400000]);
    assert.equal(catalog.find((p) => p.id === "lmstudio")!.local, true);
    assert.equal(catalog.find((p) => p.id === "lmstudio")!.keyName, null);
    assert.equal(catalog.find((p) => p.id === "exaone")!.baseUrl, "https://api.exaone.ai/v1");
    assert.deepEqual(catalogFromModelsDev("garbage"), []);
  });

  it("the catalogue cannot name the secret: a key is stored under the provider's own id, so no entry can ask for another provider's key", () => {
    assert.equal(catalog.find((p) => p.id === "openai-fast")!.keyName, "AEON_EP_OPENAI_FAST_KEY", "not the OPENAI_API_KEY it asked for");
    assert.deepEqual([keyNameFor("openai"), keyNameFor("ollama-cloud"), keyNameFor("302ai")], ["AEON_EP_OPENAI_KEY", "AEON_EP_OLLAMA_CLOUD_KEY", "AEON_EP_302AI_KEY"]);
    assert.notEqual(keyNameFor("302ai"), keyNameFor("p-302ai"), "no two ids share a secret");
    for (const id of ["openai-compat", "exaone", "openai"]) assert.ok(!["OPENAI_COMPAT_API_KEY", "EXAONE_API_KEY", "OPENAI_API_KEY"].includes(keyNameFor(id)), "never a name used for something else");
    const names = catalog.map((p) => p.keyName).filter((k) => k !== null);
    assert.equal(new Set(names).size, names.length, "one secret per provider");
  });

  it("a provider whose address changed must be agreed to again before its key goes there", () => {
    const first = parseConfig(selectModel(DEFAULT_CONFIG, { provider: "upstage", model: "solar-pro3", consent: true }, catalog));
    const moved = catalog.map((p) => (p.id === "upstage" ? { ...p, baseUrl: "https://attacker.example/v1" } : p));
    assert.throws(() => selectModel(first, { provider: "upstage", model: "solar-pro3" }, moved), /address of this provider changed from api.upstage.ai to attacker.example/);
    assert.equal(parseConfig(selectModel(first, { provider: "upstage", model: "solar-pro3", consent: true }, moved)).endpoints![0]!.baseUrl, "https://attacker.example/v1");
    assert.equal(parseConfig(selectModel(first, { provider: "upstage", model: "solar-mini" }, catalog)).endpoints![0]!.model, "solar-mini", "same address: no second question");
  });

  it("picking a model puts it first, stores no key, and needs consent once for a provider outside this machine", () => {
    assert.throws(() => selectModel(DEFAULT_CONFIG, { provider: "openai", model: "gpt-think" }, catalog), /consent required/);
    const a = parseConfig(selectModel(DEFAULT_CONFIG, { provider: "openai", model: "gpt-think", reasoningEffort: "high", consent: true }, catalog));
    assert.deepEqual(a.providers, ["openai"], "fallback is off: the others would never be used");
    assert.deepEqual(selectModel({ ...DEFAULT_CONFIG, fallbackAllowed: true }, { provider: "ollama", model: "m" }, catalog).providers, ["ollama", "exaone"]);
    assert.deepEqual(a.dataConsent, ["openai"]);
    assert.deepEqual(a.endpoints, [{ id: "openai", baseUrl: "https://api.openai.com/v1", model: "gpt-think", name: "OpenAI", keyName: "AEON_EP_OPENAI_KEY", reasoningEffort: "high" }]);
    assert.deepEqual(currentModel(a, catalog), { provider: "openai", providerName: "OpenAI", model: "gpt-think", reasoningEffort: "high" });

    const b = parseConfig(selectModel(a, { provider: "openai", model: "gpt-fast" }, catalog));
    assert.equal(b.endpoints!.length, 1, "the endpoint is replaced, not duplicated");
    assert.equal(b.endpoints![0]!.reasoningEffort, undefined);

    const local = parseConfig(selectModel(b, { provider: "lmstudio", model: "qwen" }, catalog));
    assert.deepEqual(local.providers, ["lmstudio"]);
    assert.deepEqual(local.dataConsent, ["openai"], "a model server on this machine needs no consent");

    const exa = parseConfig(selectModel(local, { provider: "exaone", model: "k-exaone_v2.5", reasoningEffort: "max", consent: true }, catalog));
    assert.equal(exa.exaoneReasoning, "max");
    assert.throws(() => selectModel(DEFAULT_CONFIG, { provider: "exaone", model: "k-exaone_v2.5", reasoningEffort: "medium", consent: true }, catalog), /low, high, max/);
    assert.throws(() => selectModel(DEFAULT_CONFIG, { provider: "nope", model: "m" }, catalog), /unknown provider/);
    assert.equal(parseConfig(selectModel(DEFAULT_CONFIG, { provider: "ollama", model: "exaone3.5:7.8b" }, catalog)).ollamaModel, "exaone3.5:7.8b");
  });

  it("accepts a hand-typed endpoint, but only https (or localhost) and never a key in the settings", () => {
    const c = parseConfig(selectModel(DEFAULT_CONFIG, { provider: "my-vllm", model: "qwen3", consent: true, custom: { name: "사내 vLLM", baseUrl: "https://llm.example.com/v1/" } }, catalog));
    assert.deepEqual(c.endpoints, [{ id: "my-vllm", baseUrl: "https://llm.example.com/v1", model: "qwen3", name: "사내 vLLM", keyName: "AEON_EP_MY_VLLM_KEY" }]);
    assert.equal(parseConfig(selectModel(DEFAULT_CONFIG, { provider: "open-box", model: "m", consent: true, custom: { baseUrl: "https://open.example/v1", needsKey: false } }, catalog)).endpoints![0]!.keyName, undefined);
    assert.throws(() => selectModel(DEFAULT_CONFIG, { provider: "Bad.Id", model: "m", consent: true, custom: { baseUrl: "https://a.example/v1" } }, catalog), /provider id/);
    const bad = (e: unknown) => assert.throws(() => parseConfig({ ...DEFAULT_CONFIG, endpoints: [e] }));
    bad({ id: "x", baseUrl: "http://llm.example.com/v1", model: "m" });
    bad({ id: "x", baseUrl: "https://user:pw@llm.example.com/v1", model: "m" });
    bad({ id: "x", baseUrl: "https://a.example/v1", model: "m", apiKey: "sk-secret" });
    bad({ id: "evil", baseUrl: "https://attacker.example/v1", model: "m", keyName: "EXAONE_API_KEY" });
    bad({ id: "x", baseUrl: "https://a.example/v1", model: "m", keyName: "AEON_EP_OTHER_KEY" });
    bad({ id: "exaone", baseUrl: "https://a.example/v1", model: "m" });
    bad({ id: "x", baseUrl: "https://a.example/v1", model: "m", reasoningEffort: "extreme" });
    assert.throws(() => parseConfig({ ...DEFAULT_CONFIG, providers: ["ghost"] }), /unknown provider/);
  });

  it("the runtime wires a chosen endpoint with its key and marks only non-local ones external", () => {
    const cfg = parseConfig(selectModel(DEFAULT_CONFIG, { provider: "upstage", model: "solar-pro3", consent: true }, catalog));
    assert.throws(() => buildProviders({ workspace: ".", providers: ["upstage"], fallbackAllowed: false, dataConsent: [], endpoints: cfg.endpoints!, env: {} }, new Redactor()), /needs AEON_EP_UPSTAGE_KEY/);
    const [p] = buildProviders({ workspace: ".", providers: ["upstage"], fallbackAllowed: false, dataConsent: [], endpoints: cfg.endpoints!, env: { AEON_EP_UPSTAGE_KEY: "up_key_123456" } }, new Redactor());
    assert.deepEqual([p!.id, p!.external, p!.capabilities().modelId], ["upstage", true, "solar-pro3"]);
    const lm = parseConfig(selectModel(DEFAULT_CONFIG, { provider: "lmstudio", model: "qwen" }, catalog));
    assert.equal(buildProviders({ workspace: ".", providers: ["lmstudio"], fallbackAllowed: false, dataConsent: [], endpoints: lm.endpoints!, env: {} }, new Redactor())[0]!.external, false);
  });

  it("drops reasoning_effort and retries once when a service rejects it", async () => {
    const bodies: Record<string, unknown>[] = [];
    const fetchImpl = (async (_url: unknown, init?: { body?: unknown }) => {
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
      bodies.push(body);
      if ("reasoning_effort" in body) return new Response(JSON.stringify({ error: "Unrecognized request argument: reasoning_effort" }), { status: 400 });
      return new Response(JSON.stringify({ choices: [{ message: { content: "ok" } }], usage: {} }), { status: 200 });
    }) as typeof fetch;
    const p = openAiCompatible({ baseUrl: "https://a.example/v1", model: "m", external: true, reasoningEffort: "high", fetch: fetchImpl });
    assert.equal((await p.chat({ messages: [{ role: "user", content: "hi" }] })).content, "ok");
    assert.equal((await p.chat({ messages: [{ role: "user", content: "again" }] })).content, "ok");
    assert.deepEqual(bodies.map((b) => "reasoning_effort" in b), [true, false, false], "sent once, then left out for good");
  });

  it("owner preferences are added to the system prompt as wording only", () => {
    assert.deepEqual(profileLines(undefined), []);
    assert.deepEqual(profileLines({}), []);
    const lines = profileLines({ userName: "지우<님>", agentName: "아온", tone: "polite", detail: "brief", language: "ko", about: "개발자\n야간 근무" });
    assert.match(lines[0]!, /never change the rules above/);
    assert.ok(lines.includes('- Address the owner as "지우 님".'), "tag brackets are removed");
    assert.ok(lines.includes('- Your name is "아온".'));
    assert.ok(lines.some((l) => l.includes("개발자 야간 근무")), "notes are flattened to one line");
    assert.throws(() => parseConfig({ ...DEFAULT_CONFIG, profile: { tone: "rude" } }));
    assert.throws(() => parseConfig({ ...DEFAULT_CONFIG, profile: { userName: "a\nb" } }));
    assert.equal(parseConfig({ ...DEFAULT_CONFIG, profile: { userName: "  " } }).profile, undefined, "empty values are not stored");
  });
});

describe("Owner profile reaches the model", () => {
  it("every model call of a task carries the saved name, tone and interests in its system prompt", async () => {
    const { dir, cleanup } = tempDir();
    mkdirSync(join(dir, "ws"));
    const model = new MockProvider("m", [JSON.stringify({ plan: ["answer"], criteria: [] }), JSON.stringify({ action: "finish", summary: "지우님, 끝났어요." })]);
    const profile = parseConfig({ ...DEFAULT_CONFIG, profile: { userName: "지우", tone: "polite", language: "ko", interests: ["개발", " 여행 ", "개발"] } }).profile!;
    assert.deepEqual(profile.interests, ["개발", "여행"], "trimmed and de-duplicated");
    const rt = createRuntime({ workspace: join(dir, "ws"), providers: ["m"], providerOverrides: [model], fallbackAllowed: false, dataConsent: [], store: new MemoryStore(), clock: new ManualClock(Date.now()), profile });
    const task = await rt.engine.submit({ goal: "인사해 줘", criteria: [] });
    for (let i = 0; i < 6 && (await rt.store.getTask(task.id))?.status !== "COMPLETED"; i++) await rt.engine.tick();
    assert.equal((await rt.store.getTask(task.id))?.status, "COMPLETED");
    assert.ok(model.calls.length >= 2);
    for (const call of model.calls) {
      const system = call.messages[0]!.content;
      assert.match(system, /Address the owner as "지우"/);
      assert.match(system, /존댓말/);
      assert.match(system, /The owner's interests: 개발, 여행\./);
      assert.match(system, /Write text meant for the owner in Korean/);
    }
    assert.throws(() => parseConfig({ ...DEFAULT_CONFIG, profile: { interests: Array.from({ length: 13 }, (_, i) => `t${i}`) } }));
    cleanup();
  });
});

describe("ProviderCatalog (cache)", () => {
  it("fetches once a day, falls back to the last copy, and backs off after a failure", async () => {
    const { dir, cleanup } = tempDir();
    let now = 1_000_000;
    let calls = 0;
    let fail = false;
    const fetcher = async (): Promise<Fetched> => {
      calls++;
      if (fail) throw new Error("offline");
      const bytes = Buffer.from(JSON.stringify(MODELS_DEV));
      return { status: 200, text: bytes.toString(), bytes, truncated: false };
    };
    const make = () => new ProviderCatalog({ configDir: dir, fetcher, url: "https://models.test/api.json", now: () => now });
    const a = make();
    assert.ok((await a.providers()).some((p) => p.id === "upstage"));
    await a.providers();
    assert.equal(calls, 1);
    assert.ok((await make().providers()).some((p) => p.id === "upstage"), "a new process reads the disk copy");
    assert.equal(calls, 1);
    now += 24 * 3600_000 + 1;
    fail = true;
    const b = make();
    assert.ok((await b.providers()).some((p) => p.id === "upstage"), "stale copy is better than nothing");
    assert.equal(b.error, "offline");
    await b.providers();
    assert.equal(calls, 2, "no second attempt within ten minutes");
    cleanup();
    const empty = tempDir();
    const c = new ProviderCatalog({ configDir: empty.dir, fetcher, url: "https://models.test/api.json", now: () => now });
    assert.deepEqual((await c.providers()).map((p) => p.id), ["exaone", "ollama", "llamacpp"], "offline with no copy: built-ins only");
    empty.cleanup();
  });
});

/** What a device does: one-time key, ECDH with the pinned server key, AES-256-GCM. Mirrors ui/.../Sealing.kt. */
function sealKey(shared: Buffer, direction: string, epk: Buffer, serverKey: string): Buffer {
  return createHash("sha256").update(`AEON-SEAL-V1|${direction}|`).update(shared).update(epk).update(Buffer.from(serverKey, "base64")).digest();
}
function clientSeal(serverKey: string, plaintext: string, aad: string) {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const epk = publicKey.export({ type: "spki", format: "der" });
  const shared = diffieHellman({ privateKey, publicKey: createPublicKey({ key: Buffer.from(serverKey, "base64"), format: "der", type: "spki" }) });
  const iv = randomBytes(12);
  const c = createCipheriv("aes-256-gcm", sealKey(shared, "c2s", epk, serverKey), iv);
  c.setAAD(Buffer.from(aad));
  const ct = Buffer.concat([c.update(plaintext, "utf8"), c.final(), c.getAuthTag()]);
  return { epk: epk.toString("base64"), iv: iv.toString("base64"), ct: ct.toString("base64") };
}
function clientExchange(serverKey: string) {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const epk = publicKey.export({ type: "spki", format: "der" });
  return {
    epk: epk.toString("base64"),
    open(box: { iv: string; ct: string }, aad: string): string {
      const shared = diffieHellman({ privateKey, publicKey: createPublicKey({ key: Buffer.from(serverKey, "base64"), format: "der", type: "spki" }) });
      const data = Buffer.from(box.ct, "base64");
      const d = createDecipheriv("aes-256-gcm", sealKey(shared, "s2c", epk, serverKey), Buffer.from(box.iv, "base64"));
      d.setAAD(Buffer.from(aad));
      d.setAuthTag(data.subarray(data.length - 16));
      return Buffer.concat([d.update(data.subarray(0, data.length - 16)), d.final()]).toString("utf8");
    },
  };
}

describe("Sealed boxes", () => {
  it("opens what a device sealed, and refuses a wrong context, a changed byte or a foreign key", () => {
    const id = ServerIdentity.load(null);
    const box = clientSeal(id.publicKey, "sk-live-value", "secret|dev_1|OPENAI_API_KEY");
    assert.equal(id.open(box, "secret|dev_1|OPENAI_API_KEY").toString(), "sk-live-value");
    assert.throws(() => id.open(box, "secret|dev_2|OPENAI_API_KEY"), "bound to the device");
    assert.throws(() => id.open(box, "secret|dev_1|OTHER_KEY"), "bound to the secret name");
    const flipped = Buffer.from(box.ct, "base64");
    flipped[0] = flipped[0]! ^ 1;
    assert.throws(() => id.open({ ...box, ct: flipped.toString("base64") }, "secret|dev_1|OPENAI_API_KEY"));
    assert.throws(() => ServerIdentity.load(null).open(box, "secret|dev_1|OPENAI_API_KEY"), "another server cannot read it");
    assert.throws(() => id.open({ ...box, epk: Buffer.from("not a key").toString("base64") }, "x"));
    const ex = clientExchange(id.publicKey);
    assert.equal(ex.open(id.sealFor(ex.epk, Buffer.from("ABCD"), "pairing-code|dev_1"), "pairing-code|dev_1"), "ABCD");
    assert.throws(() => ex.open(id.sealFor(ex.epk, Buffer.from("ABCD"), "pairing-code|dev_1"), "pairing-code|dev_2"));
  });

  it("keeps the same key across restarts", () => {
    const { dir, cleanup } = tempDir();
    const file = join(dir, "cfg", "server-key.json");
    const a = ServerIdentity.load(file);
    assert.equal(ServerIdentity.load(file).publicKey, a.publicKey);
    assert.ok(!readFileSync(file, "utf8").includes(a.publicKey), "only the private half is stored");
    cleanup();
  });
});

class Client {
  readonly priv: KeyObject;
  readonly pub: string;
  id = "";
  serverKey = "";
  readonly name: string;
  constructor(name: string) {
    this.name = name;
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    this.priv = privateKey;
    this.pub = publicKey.export({ type: "spki", format: "der" }).toString("base64");
  }
  async pair(base: string, code: string) {
    const clientNonce = randomBytes(16).toString("base64");
    const proof = PairingManager.clientProof(PairingManager.normalize(code), this.name, this.pub, clientNonce);
    const res = await fetch(`${base}/v1/pair`, { method: "POST", body: JSON.stringify({ deviceName: this.name, publicKey: this.pub, clientNonce, proof }) });
    const body = (await res.json()) as Record<string, any>;
    if (res.status === 201) {
      this.id = body.deviceId;
      assert.equal(body.serverKeyProof, PairingManager.serverKeyProof(PairingManager.normalize(code), body.serverKey, clientNonce, this.id), "the key is vouched for by the pairing code");
      this.serverKey = body.serverKey;
    }
    return { status: res.status, body };
  }
  /** A signed GET whose answer is bytes (an image). */
  async callBytes(base: string, clock: ManualClock, path: string) {
    const ts = String(clock.now());
    const nonce = randomBytes(12).toString("hex");
    const signature = sign("sha256", Buffer.from(canonicalRequest("GET", path, ts, nonce, "")), this.priv).toString("base64");
    const res = await fetch(`${base}${path}`, { headers: { "x-aeon-device": this.id, "x-aeon-timestamp": ts, "x-aeon-nonce": nonce, "x-aeon-signature": signature } });
    return { status: res.status, type: res.headers.get("content-type"), bytes: Buffer.from(await res.arrayBuffer()) };
  }

  async call(base: string, clock: ManualClock, method: string, path: string, json?: unknown, raw?: Buffer) {
    const body = raw ?? (json === undefined ? "" : JSON.stringify(json));
    const ts = String(clock.now());
    const nonce = randomBytes(12).toString("hex");
    const signature = sign("sha256", Buffer.from(canonicalRequest(method, path, ts, nonce, body)), this.priv).toString("base64");
    const res = await fetch(`${base}${path}`, { method, headers: { "x-aeon-device": this.id, "x-aeon-timestamp": ts, "x-aeon-nonce": nonce, "x-aeon-signature": signature }, ...(body.length ? { body } : {}) });
    const text = await res.text();
    return { status: res.status, body: text ? (JSON.parse(text) as Record<string, any>) : {} };
  }
}

async function boot(opts: { local?: boolean; maxUploadBytes?: number; feedFetch?: FeedFetch; feedArticles?: ArticleFetcher; modelReplies?: string[] } = {}) {
  const { dir, cleanup } = tempDir();
  const clock = new ManualClock(Date.parse("2026-11-11T03:04:05Z"));
  const workspace = join(dir, "ws");
  mkdirSync(workspace);
  const rt = createRuntime({ workspace, providers: ["m"], providerOverrides: [new MockProvider("m", opts.modelReplies ?? [])], fallbackAllowed: false, dataConsent: [], store: new MemoryStore(), clock, skillsDir: join(dir, "cfg", "skills"), skillsLockFile: join(dir, "cfg", "skills-lock.json"), memoryFile: join(dir, "cfg", "memory.json") });
  const registry = new DeviceRegistry(null);
  const pairing = new PairingManager(registry);
  const secrets = new MemorySecretStore();
  const identity = ServerIdentity.load(null);
  const fetcher = async (): Promise<Fetched> => {
    const bytes = Buffer.from(JSON.stringify(MODELS_DEV));
    return { status: 200, text: bytes.toString(), bytes, truncated: false };
  };
  let changed = 0;
  const server = new AeonServer({
    getRuntime: () => rt, secrets, configDir: join(dir, "cfg"), registry, pairing, clock, tickIntervalMs: 0, identity,
    providers: new ProviderCatalog({ configDir: join(dir, "cfg"), fetcher, url: "https://models.test/api.json" }),
    connections: new ConnectionService({ configDir: join(dir, "cfg"), fetcher }),
    addresses: () => ["http://192.168.0.12:7421"],
    isLocalRequest: () => opts.local === true,
    ...(opts.feedFetch ? { feed: new FeedPipeline({ store: new FeedStore(join(dir, "cfg", "feed-posts.json")), adapters: [new GoogleNewsAdapter({ fetchImpl: opts.feedFetch, ...(opts.feedArticles ? { articles: opts.feedArticles } : {}) })], images: new ImageStore(join(dir, "cfg", "feed-images")), now: () => clock.now() }) } : {}),
    onConfigChanged: () => void changed++,
    ...(opts.maxUploadBytes ? { maxUploadBytes: opts.maxUploadBytes } : {}),
  });
  const base = `http://127.0.0.1:${(await server.listen(0)).port}`;
  const owner = new Client("owner-phone");
  assert.equal((await owner.pair(base, pairing.newCode(clock.now()).code)).status, 201);
  return { server, rt, base, clock, dir, workspace, registry, pairing, secrets, identity, owner, changed: () => changed, cfgDir: join(dir, "cfg"), close: async () => (await server.close(), cleanup()) };
}

describe("Setting things up from any paired device", () => {
  it("pairing hands the device the server's sealing key, proven with the pairing code", async () => {
    const t = await boot();
    assert.equal(t.owner.serverKey, t.identity.publicKey);
    const st = await t.owner.call(t.base, t.clock, "GET", "/v1/status");
    assert.deepEqual([st.body.admin, st.body.sealing, st.body.deviceName, st.body.addresses], [true, true, "owner-phone", ["http://192.168.0.12:7421"]]);
    assert.equal(typeof st.body.host.platform, "string");
    await t.close();
  });

  it("a key typed on a phone reaches the secret store sealed, bound to that device and that name", async () => {
    const t = await boot();
    const sealed = clientSeal(t.owner.serverKey, "sk-from-phone-123", `secret|${t.owner.id}|OPENAI_API_KEY`);
    assert.equal((await t.owner.call(t.base, t.clock, "PUT", "/v1/secrets/OPENAI_API_KEY", { sealed })).status, 204);
    assert.equal(await t.secrets.get("OPENAI_API_KEY"), "sk-from-phone-123");
    assert.equal((await t.owner.call(t.base, t.clock, "PUT", "/v1/secrets/GROQ_API_KEY", { sealed })).status, 400, "a box for another name is refused");
    assert.equal(await t.secrets.get("GROQ_API_KEY"), null);
    assert.equal((await t.owner.call(t.base, t.clock, "PUT", "/v1/secrets/GROQ_API_KEY", { value: "plain" })).status, 400);
    assert.equal((await t.owner.call(t.base, t.clock, "DELETE", "/v1/secrets/OPENAI_API_KEY")).status, 204);
    await t.close();
  });

  it("a paired device can let another device in: the code comes back sealed and pairs exactly once", async () => {
    const t = await boot();
    assert.equal((await t.owner.call(t.base, t.clock, "POST", "/v1/pairing-codes", {})).status, 400, "no code in the clear over the network");
    const ex = clientExchange(t.owner.serverKey);
    const r = await t.owner.call(t.base, t.clock, "POST", "/v1/pairing-codes", { epk: ex.epk, admin: false });
    assert.equal(r.status, 201);
    assert.equal(r.body.code, undefined);
    assert.deepEqual(r.body.addresses, ["http://192.168.0.12:7421"]);
    const code = ex.open(r.body.sealed, `pairing-code|${t.owner.id}`);
    assert.match(code, /^[A-Z2-7]{16}$/);
    const tablet = new Client("living-room-tablet");
    assert.equal((await tablet.pair(t.base, code)).status, 201);
    assert.equal((await new Client("thief").pair(t.base, code)).status, 403, "single use");
    const list = (await t.owner.call(t.base, t.clock, "GET", "/v1/devices")).body.devices as { id: string; name: string; admin: boolean; current: boolean }[];
    assert.deepEqual(list.map((d) => [d.name, d.admin, d.current]), [["owner-phone", true, true], ["living-room-tablet", false, false]]);

    assert.equal((await tablet.call(t.base, t.clock, "GET", "/v1/status")).body.admin, false);
    assert.equal((await t.owner.call(t.base, t.clock, "PATCH", `/v1/devices/${tablet.id}`, { name: "거실 태블릿", admin: true })).body.device.name, "거실 태블릿");
    assert.equal((await tablet.call(t.base, t.clock, "GET", "/v1/status")).body.admin, true);
    assert.equal((await t.owner.call(t.base, t.clock, "PATCH", `/v1/devices/${t.owner.id}`, { admin: false })).status, 409, "no locking yourself out");
    assert.equal((await t.owner.call(t.base, t.clock, "PATCH", `/v1/devices/${tablet.id}`, { admin: false })).status, 200);
    assert.equal((await tablet.call(t.base, t.clock, "DELETE", `/v1/devices/${tablet.id}`)).status, 204, "any device may sign itself out");
    assert.equal((await tablet.call(t.base, t.clock, "GET", "/v1/status")).status, 401);
    await t.close();
  });

  it("on the machine itself the code can be shown directly", async () => {
    const t = await boot({ local: true });
    const r = await t.owner.call(t.base, t.clock, "POST", "/v1/pairing-codes", {});
    assert.match(r.body.code, /^[A-Z2-7]{16}$/);
    await t.close();
  });

  it("lists providers from the catalogue and switches the model, asking for consent the first time", async () => {
    const t = await boot();
    await t.secrets.set("AEON_EP_UPSTAGE_KEY", "up_key_abcdef");
    const list = await t.owner.call(t.base, t.clock, "GET", "/v1/providers");
    const row = (id: string) => (list.body.providers as Record<string, any>[]).find((p) => p.id === id)!;
    assert.deepEqual([row("upstage").hasKey, row("openai").hasKey, row("ollama").hasKey, row("ollama").local], [true, false, true, true]);
    assert.deepEqual(list.body.current, { provider: "exaone", providerName: "LG EXAONE", model: "k-exaone_v2.5", reasoningEffort: "low" });
    assert.ok(!JSON.stringify(list.body).includes("up_key_abcdef"));
    assert.deepEqual((await t.owner.call(t.base, t.clock, "GET", "/v1/providers/upstage/models")).body.models.map((m: { id: string }) => m.id), ["solar-pro3"]);
    assert.equal((await t.owner.call(t.base, t.clock, "GET", "/v1/providers/nope/models")).status, 404);

    const noConsent = await t.owner.call(t.base, t.clock, "POST", "/v1/model", { provider: "upstage", model: "solar-pro3" });
    assert.equal(noConsent.status, 400);
    assert.match(noConsent.body.error, /consent required/);
    const ok = await t.owner.call(t.base, t.clock, "POST", "/v1/model", { provider: "upstage", model: "solar-pro3", reasoningEffort: "medium", consent: true });
    assert.equal(ok.status, 200);
    assert.deepEqual(ok.body.current, { provider: "upstage", providerName: "Upstage", model: "solar-pro3", reasoningEffort: "medium" });
    assert.deepEqual(loadConfig(t.cfgDir).providers, ["upstage"]);
    assert.equal(t.changed(), 1, "the runtime is rebuilt for the next task");
    assert.equal((await t.owner.call(t.base, t.clock, "POST", "/v1/model", { provider: "upstage", model: "solar-pro3", reasoningEffort: "ultra" })).status, 400);
    await t.close();
  });

  it("declining internet lookups stops catalogue and logo requests; only an allowed device can change it", async () => {
    const t = await boot();
    const before = await t.owner.call(t.base, t.clock, "GET", "/v1/providers");
    assert.equal(before.body.online, true);
    assert.ok(before.body.providers.length > 3);
    assert.ok(before.body.providers.every((p: { icon: string | null }) => p.icon === null || /^\/v1\/icons\/[a-f0-9]{24}$/.test(p.icon)), "every provider points at its logo, which is fetched on first use");
    assert.ok(before.body.providers.some((p: { icon: string | null }) => p.icon !== null));
    const off = await t.owner.call(t.base, t.clock, "PUT", "/v1/config", { ...DEFAULT_CONFIG, onlineLookups: false });
    assert.equal(off.status, 200);
    assert.equal(loadConfig(t.cfgDir).onlineLookups, false);
    const offList = (await t.owner.call(t.base, t.clock, "GET", "/v1/providers")).body;
    assert.equal(offList.online, false);
    assert.ok(offList.providers.every((p: { icon: string | null }) => p.icon === null), "no logo is offered, so none is fetched");
    const ex = clientExchange(t.owner.serverKey);
    const code = ex.open((await t.owner.call(t.base, t.clock, "POST", "/v1/pairing-codes", { epk: ex.epk, admin: false })).body.sealed, `pairing-code|${t.owner.id}`);
    const limited = new Client("tablet");
    await limited.pair(t.base, code);
    const on = await limited.call(t.base, t.clock, "PUT", "/v1/config", { ...DEFAULT_CONFIG });
    assert.equal(on.status, 403);
    assert.match(on.body.error, /onlineLookups/);
    assert.equal(parseConfig({ ...DEFAULT_CONFIG, onlineLookups: true }).onlineLookups, undefined, "yes is the default and is not stored");
    await t.close();
  });

  it("the owner can read, correct and delete skills but not create them; the agent makes them", async () => {
    const t = await boot();
    const put = (folder: string, body: Record<string, unknown>) => t.owner.call(t.base, t.clock, "PUT", `/v1/skills/${folder}`, body);
    assert.deepEqual((await t.owner.call(t.base, t.clock, "GET", "/v1/skills")).body.skills, []);
    assert.equal((await put("meeting-notes", { description: "x", body: "y" })).status, 404, "no create from the API: skills are made by the agent");

    const store = new SkillStore(join(t.cfgDir, "skills"));
    store.write({ name: "meeting-notes", description: "Summarise meeting notes into decisions and to-dos. Use when the owner asks for a summary of meetings.", body: "# Steps\n1. Read the notes", metadata: { author: "aeon" } }, true, { "references/FORMAT.md": "# Format\n" });
    mkdirSync(join(t.cfgDir, "skills", "old-format"), { recursive: true });
    writeFileSync(join(t.cfgDir, "skills", "old-format", "SKILL.md"), "---\nname: Old Format\n---\nbody\n");

    const list = (await t.owner.call(t.base, t.clock, "GET", "/v1/skills")).body.skills as { folder: string; problems: string[]; files: string[] }[];
    assert.deepEqual(list.map((x) => [x.folder, x.problems.length === 0, x.files]), [["meeting-notes", true, ["references/FORMAT.md"]], ["old-format", false, []]], "a skill that does not follow the format is listed with what is wrong");
    assert.match(list[1]!.problems.join(" "), /name|description/);
    const one = (await t.owner.call(t.base, t.clock, "GET", "/v1/skills/meeting-notes")).body.skill;
    assert.deepEqual([one.name, one.metadata, one.body], ["meeting-notes", { author: "aeon" }, "# Steps\n1. Read the notes\n"]);

    const edited = await put("meeting-notes", { description: "Summarise meeting notes. Use for meetings.", body: "수정" });
    assert.equal(edited.status, 200);
    assert.match(readFileSync(join(t.cfgDir, "skills", "meeting-notes", "SKILL.md"), "utf8"), /^---\nname: meeting-notes\ndescription: Summarise meeting notes\. Use for meetings\.\nmetadata:\n {2}author: "aeon"\n---\n\n수정\n$/, "front matter keeps the spec's shape; name stays the folder name");
    assert.ok(existsSync(join(t.cfgDir, "skills", "meeting-notes", "references", "FORMAT.md")), "supporting files stay");
    assert.equal((await put("meeting-notes", { description: "", body: "x" })).status, 400, "the spec needs a description");
    assert.equal((await put("meeting-notes", { description: "d", body: "x".repeat(100_001) })).status, 400);
    assert.equal((await t.owner.call(t.base, t.clock, "GET", "/v1/connections")).body.connections.filter((c: { kind: string }) => c.kind === "skill").map((c: { title: string }) => c.title).sort().join(","), "Old Format,meeting-notes", "the connection list shows both");

    assert.equal((await t.owner.call(t.base, t.clock, "DELETE", "/v1/skills/meeting-notes")).status, 204);
    assert.equal((await t.owner.call(t.base, t.clock, "DELETE", "/v1/skills/meeting-notes")).status, 404);
    assert.ok(!existsSync(join(t.cfgDir, "skills", "meeting-notes")), "the folder goes with it");

    const ex = clientExchange(t.owner.serverKey);
    const code = ex.open((await t.owner.call(t.base, t.clock, "POST", "/v1/pairing-codes", { epk: ex.epk, admin: false })).body.sealed, `pairing-code|${t.owner.id}`);
    const limited = new Client("tablet");
    await limited.pair(t.base, code);
    assert.equal((await limited.call(t.base, t.clock, "PUT", "/v1/skills/old-format", { description: "d", body: "b" })).status, 403);
    assert.equal((await limited.call(t.base, t.clock, "GET", "/v1/skills")).status, 200, "reading is allowed");
    await t.close();
  });

  it("the owner looks at a source, installs from it, switches it off and approves a skill that only appeared in the project; a limited device can do none of it", async () => {
    const t = await boot();
    const call = (m: string, p: string, b?: Record<string, unknown>) => t.owner.call(t.base, t.clock, m, p, b);
    const src = join(t.dir, "src", "tidy-notes");
    mkdirSync(join(src, "scripts"), { recursive: true });
    writeFileSync(join(src, "SKILL.md"), "---\nname: tidy-notes\ndescription: Tidy notes. Use when notes are messy.\n---\n\nRun scripts/tidy.py.\n");
    writeFileSync(join(src, "scripts", "tidy.py"), "print('tidy')\n");

    const looked = await call("POST", "/v1/skill-sources/inspect", { source: src });
    assert.equal(looked.status, 200);
    const preview = looked.body.preview;
    assert.deepEqual([preview.candidates[0].name, preview.candidates[0].scriptTexts[0].path, (await call("GET", "/v1/skills")).body.skills.length], ["tidy-notes", "scripts/tidy.py", 0], "looking installs nothing");
    assert.equal((await call("POST", "/v1/skill-sources/install", { previewId: preview.id, name: "tidy-notes", scope: "user" })).status, 201);
    assert.equal((await call("POST", "/v1/skill-sources/install", { previewId: preview.id, name: "tidy-notes", scope: "user" })).status, 409, "the same name is not overwritten silently");
    const listed = (await call("GET", "/v1/skills")).body.skills as { folder: string; scope: string; status: string; active: boolean; scripts: boolean }[];
    assert.deepEqual(listed.map((s) => [s.folder, s.scope, s.status, s.active, s.scripts]), [["tidy-notes", "user", "tracked", true, true]]);
    assert.equal((await call("GET", "/v1/skills/tidy-notes/file?scope=user&path=scripts/tidy.py")).body.text, "print('tidy')\n", "the owner can read a script before approving");
    assert.equal((await call("GET", "/v1/skills/tidy-notes/file?scope=user&path=..%2FSKILL.md")).status, 404, "only references/, assets/ and scripts/ can be read");
    assert.equal((await call("POST", "/v1/skills/tidy-notes/enable", { scope: "user", enabled: false })).body.skill.active, false);
    assert.equal((await call("POST", "/v1/skills/tidy-notes/enable", { scope: "user", enabled: true })).body.skill.active, true);

    const dropped = join(t.workspace, ".agents", "skills", "from-a-clone");
    mkdirSync(dropped, { recursive: true });
    writeFileSync(join(dropped, "SKILL.md"), "---\nname: from-a-clone\ndescription: Arrived with a repository. Use for anything.\n---\n\nbody\n");
    const clone = (await call("GET", "/v1/skills")).body.skills.find((s: { folder: string }) => s.folder === "from-a-clone");
    assert.deepEqual([clone.scope, clone.status, clone.active], ["project", "untracked", false]);
    assert.equal((await call("GET", "/v1/skills/from-a-clone?scope=project")).body.skill.status, "untracked", "an unapproved skill can be opened to be read");
    assert.equal((await call("POST", "/v1/skills/from-a-clone/enable", { scope: "project", enabled: true })).status, 409, "switching on is not approving");
    assert.equal((await call("POST", "/v1/skills/from-a-clone/trust", { scope: "project" })).status, 400, "the hash of what was shown is required");
    assert.equal((await call("POST", "/v1/skills/from-a-clone/trust", { scope: "project", hash: "0".repeat(64) })).status, 409, "a skill that is not what was shown is not approved");
    assert.equal((await call("POST", "/v1/skills/from-a-clone/trust", { scope: "project", hash: clone.hash })).body.skill.active, true);
    assert.equal((await call("GET", "/v1/skills/from-a-clone?scope=project")).body.skill.scope, "project");
    assert.equal((await call("DELETE", "/v1/skills/from-a-clone?scope=project")).status, 204);

    const ex = clientExchange(t.owner.serverKey);
    const code = ex.open((await call("POST", "/v1/pairing-codes", { epk: ex.epk, admin: false })).body.sealed, `pairing-code|${t.owner.id}`);
    const limited = new Client("tablet");
    await limited.pair(t.base, code);
    for (const [m, p, b] of [["POST", "/v1/skill-sources/inspect", { source: src }], ["POST", "/v1/skill-sources/install", { previewId: preview.id, name: "tidy-notes" }], ["POST", "/v1/skills/tidy-notes/trust", { scope: "user", hash: "x" }], ["POST", "/v1/skills/tidy-notes/enable", { scope: "user", enabled: false }], ["DELETE", "/v1/skills/tidy-notes", undefined]] as [string, string, Record<string, unknown> | undefined][]) {
      assert.equal((await limited.call(t.base, t.clock, m, p, b)).status, 403, `${m} ${p}`);
    }
    assert.equal((await call("POST", "/v1/skill-sources/inspect", { source: "https://gitlab.com/o/r" })).status, 400);
    await t.close();
  });

  it("the feed makes posts from the owner's interests on request, within its daily limits, and only an admin device changes the brief", async () => {
    const hour = new Date(Date.parse("2026-11-11T02:00:00Z")).toUTCString();
    const body = "<rss><channel><item><title>반도체 수출 두 달 연속 증가 - E경제</title><link>https://news.google.com/rss/articles/abc</link><pubDate>" + hour + "</pubDate><source url=\"https://e.example\">E경제</source></item></channel></rss>";
    const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(3000, 9)]);
    let asked = 0;
    let looked = 0;
    const articles: ArticleFetcher = async (_link, o) => (looked++, { text: "반도체 수출이 두 달 연속 늘었다는 집계가 나왔습니다.", image: o.image ? { bytes: jpeg, type: "image/jpeg" } : null });
    const t = await boot({ feedFetch: async () => { asked++; return new Response(body, { status: 200 }); }, feedArticles: articles });
    const call = (m: string, p: string, b?: Record<string, unknown>) => t.owner.call(t.base, t.clock, m, p, b);
    const empty = (await call("GET", "/v1/feed")).body;
    assert.deepEqual([empty.posts, empty.interests, empty.online, empty.settings.auto, empty.settings.dailyRuns, empty.status.nextRunAt], [[], [], true, true, 4, null], "nothing yet, and no interests");
    const none = await call("POST", "/v1/feed/generate");
    assert.deepEqual([none.status, none.body.report.status, none.body.report.reason], [200, "skipped", "no_interests"]);

    const cfg = (await call("GET", "/v1/config")).body.config;
    assert.equal((await call("PUT", "/v1/config", { ...cfg, profile: { interests: ["AI", "여행"] } })).status, 200);
    const first = await call("POST", "/v1/feed/generate");
    assert.equal(first.status, 200);
    // The Mock model has nothing scripted, so it cannot write: plain headlines, labelled, one per distinct story.
    assert.deepEqual([first.body.report.status, first.body.report.mode, first.body.report.made], ["made", "headlines", 1]);
    const post = first.body.posts[0];
    assert.deepEqual([post.written, post.body, post.title, post.sources[0].url, post.image], [false, "", "반도체 수출 두 달 연속 증가", "https://news.google.com/rss/articles/abc", true]);
    assert.equal(asked, 2);
    const img = await t.owner.callBytes(t.base, t.clock, `/v1/feed/image/${post.id}`);
    assert.deepEqual([img.status, img.type, img.bytes.length], [200, "image/jpeg", jpeg.length], "the phone gets the picture from the server, never from the publisher");
    assert.equal((await t.owner.callBytes(t.base, t.clock, "/v1/feed/image/000000000000")).status, 404);
    assert.ok([400, 404].includes((await t.owner.callBytes(t.base, t.clock, "/v1/feed/image/..%2F..%2Fconfig")).status), "a path that is not an image id is refused");
    const again = await call("POST", "/v1/feed/generate");
    assert.deepEqual([again.body.report.status, again.body.report.reason, asked], ["skipped", "gap", 2], "a second run right away is refused without asking the source");
    const got = (await call("GET", "/v1/feed")).body;
    assert.deepEqual([got.status.today.runs, got.status.today.posts, got.status.caps, got.queries[0]], [1, 1, { runs: 4, posts: 8 }, { interest: "AI", query: "인공지능" }], "what is searched is shown: only the interest labels");
    assert.ok(got.status.nextRunAt > 0, "the timer's next run is known");

    // The brief and the limits: any device reads them, the machine or an admin device changes them.
    const ex = clientExchange(t.owner.serverKey);
    const code = ex.open((await call("POST", "/v1/pairing-codes", { epk: ex.epk, admin: false })).body.sealed, `pairing-code|${t.owner.id}`);
    const tablet = new Client("tablet");
    await tablet.pair(t.base, code);
    assert.equal((await tablet.call(t.base, t.clock, "GET", "/v1/feed")).body.settings.brief.length > 5, true);
    assert.equal((await tablet.call(t.base, t.clock, "PUT", "/v1/feed/settings", { brief: "내 마음대로" })).status, 403);
    const put = await call("PUT", "/v1/feed/settings", { brief: "  경제 소식만\n짧게  ", dailyRuns: 2, auto: false });
    assert.deepEqual([put.status, put.body.settings.brief, put.body.settings.dailyRuns, put.body.settings.auto], [200, "경제 소식만 짧게", 2, false]);
    for (const bad of [{ dailyRuns: 99 }, { auto: "yes" }, { intervalMinutes: 5 }]) assert.equal((await call("PUT", "/v1/feed/settings", bad)).status, 400, JSON.stringify(bad));
    assert.equal((await call("GET", "/v1/feed")).body.status.nextRunAt, null, "automatic runs off: no next run");

    // Switching the internet off stops runs and the timer's plan.
    t.clock.advance(10 * 60_000);
    const before = looked;
    assert.equal((await call("PUT", "/v1/config", { ...cfg, profile: { interests: ["AI"] }, onlineLookups: false })).status, 200);
    const off = await call("POST", "/v1/feed/generate");
    assert.deepEqual([off.body.report.reason, looked, off.body.online], ["offline", before, false]);
    await t.close();
  });

  it("the feed's own timer makes posts when they are due, on its own and without any notification, and obeys the owner's switch", async () => {
    const hour = new Date(Date.parse("2026-11-11T02:00:00Z")).toUTCString();
    const body = "<rss><channel><item><title>반도체 수출 두 달 연속 증가 - E경제</title><link>https://news.google.com/rss/articles/abc</link><pubDate>" + hour + "</pubDate><source url=\"https://e.example\">E경제</source></item></channel></rss>";
    const t = await boot({ feedFetch: async () => new Response(body, { status: 200 }) });
    const call = (m: string, p: string, b?: Record<string, unknown>) => t.owner.call(t.base, t.clock, m, p, b);
    const cfg = (await call("GET", "/v1/config")).body.config;
    assert.equal((await call("PUT", "/v1/config", { ...cfg, profile: { interests: ["AI"] } })).status, 200);
    await t.server.feedTick();
    const posts = (await call("GET", "/v1/feed")).body.posts;
    assert.equal(posts.length, 1, "nobody asked: the timer made it");
    assert.equal((await call("GET", "/v1/tasks")).body.tasks.length, 0, "it is not a task and does not appear among them");
    assert.equal((await call("GET", "/v1/approvals?status=pending")).body.approvals.length, 0);
    await t.server.feedTick();
    t.clock.advance(7 * 3_600_000);
    await call("PUT", "/v1/feed/settings", { auto: false });
    await t.server.feedTick();
    assert.equal((await call("GET", "/v1/feed")).body.status.today.runs, 1, "the interval had passed, but automatic runs are off");
    await call("PUT", "/v1/feed/settings", { auto: true });
    await t.server.feedTick();
    assert.equal((await call("GET", "/v1/feed")).body.status.today.runs, 2, "on again and the interval is over: one more run");
    await t.close();
  });

  it("reading, liking, removing and moving feed posts work from any paired device; the taste summary is read by all and changed by an admin only", async () => {
    const hour = new Date(Date.parse("2026-11-11T02:00:00Z")).toUTCString();
    const item = (n: number, title: string) => "<item><title>" + title + " - E경제</title><link>https://news.google.com/rss/articles/p" + n + "</link><pubDate>" + hour + "</pubDate><source url='https://e.example'>E경제</source></item>";
    const body = "<rss><channel>" + item(1, "반도체 수출 두 달 연속 증가") + item(2, "항공권 평균 가격 지난달보다 4% 내려") + item(3, "신약 임상 3상 통과 소식 발표") + "</channel></rss>";
    const t = await boot({ feedFetch: async () => new Response(body, { status: 200 }) });
    const call = (m: string, p: string, b?: Record<string, unknown>) => t.owner.call(t.base, t.clock, m, p, b);
    const cfg = (await call("GET", "/v1/config")).body.config;
    await call("PUT", "/v1/config", { ...cfg, profile: { interests: ["AI"] } });
    const made = await call("POST", "/v1/feed/generate");
    const ids: string[] = made.body.posts.map((p: { id: string }) => p.id);
    assert.equal(ids.length, 3);

    const ex = clientExchange(t.owner.serverKey);
    const code = ex.open((await call("POST", "/v1/pairing-codes", { epk: ex.epk, admin: false })).body.sealed, `pairing-code|${t.owner.id}`);
    const tablet = new Client("tablet");
    await tablet.pair(t.base, code);
    const tcall = (m: string, p: string, b?: Record<string, unknown>) => tablet.call(t.base, t.clock, m, p, b);

    assert.equal((await tcall("POST", `/v1/feed/posts/${ids[0]}/seen`)).status, 200);
    const afterSeen = (await call("GET", "/v1/feed")).body.posts;
    assert.equal(afterSeen.find((p: { id: string }) => p.id === ids[0]).seenAt > 0, true);
    assert.equal(afterSeen.at(-1).id, ids[0], "a read post goes below the unread ones");
    const liked = await tcall("POST", `/v1/feed/posts/${ids[1]}/react`, { reaction: "like" });
    assert.equal(liked.body.posts.find((p: { id: string }) => p.id === ids[1]).liked, true);
    assert.equal((await tcall("POST", `/v1/feed/posts/${ids[1]}/react`, { reaction: "love" })).status, 400);
    assert.equal((await tcall("POST", `/v1/feed/posts/${"0".repeat(12)}/react`, { reaction: "like" })).status, 404);
    const moved = await tcall("POST", `/v1/feed/posts/${ids[2]}/move`, { to: "top" });
    assert.equal(moved.body.posts[0].id, ids[2], "the owner's order holds today");
    assert.equal((await tcall("POST", `/v1/feed/posts/${ids[2]}/move`, { to: "sideways" })).status, 400);
    const gone = await tcall("POST", `/v1/feed/posts/${ids[2]}/react`, { reaction: "dismiss" });
    assert.deepEqual(gone.body.posts.map((p: { id: string }) => p.id).includes(ids[2]), false);

    assert.equal((await tcall("PUT", "/v1/feed/taste", { text: "짧은 글" })).status, 403);
    assert.equal((await tcall("DELETE", "/v1/feed/taste")).status, 403);
    const put = await call("PUT", "/v1/feed/taste", { text: "  짧고 분명한 글을 좋아해요  " });
    assert.deepEqual([put.body.taste.text, put.body.taste.source], ["짧고 분명한 글을 좋아해요", "owner"]);
    assert.equal((await tcall("GET", "/v1/feed")).body.taste.text, "짧고 분명한 글을 좋아해요", "any device can read it");
    assert.equal((await call("PUT", "/v1/feed/taste", { text: 5 })).status, 400);
    assert.equal((await call("DELETE", "/v1/feed/taste")).body.taste, null);
    await t.close();
  });

  it("memory suggestions, corrections and past memories: any device reads them, only the machine or an admin device changes them", async () => {
    const t = await boot();
    const call = (m: string, p: string, b?: Record<string, unknown>) => t.owner.call(t.base, t.clock, m, p, b);
    const kept = (await call("POST", "/v1/memory", { text: "My sister Mina lives in Busan", expires: "2099-01-01" })).body.item;
    assert.ok(kept.expiresAt > 0, "a memory can be added with an end date");
    assert.equal((await call("POST", "/v1/memory", { text: "A fine sentence", expires: "tomorrow" })).status, 400);
    const memory = t.rt.memory!;
    const [fix, extra] = memory.propose([{ text: "My sister Mina lives in Daegu now" }, { text: "I am allergic to peanuts" }], "task_1");
    const got = (await call("GET", "/v1/memory")).body;
    assert.deepEqual([got.proposals.length, got.proposals.map((p: { replaces?: string }) => p.replaces).sort()], [2, [kept.id, undefined].sort()], "a close suggestion says which memory it corrects");

    const ex = clientExchange(t.owner.serverKey);
    const code = ex.open((await call("POST", "/v1/pairing-codes", { epk: ex.epk, admin: false })).body.sealed, `pairing-code|${t.owner.id}`);
    const tablet = new Client("tablet");
    await tablet.pair(t.base, code);
    const tcall = (m: string, p: string, b?: Record<string, unknown>) => tablet.call(t.base, t.clock, m, p, b);
    assert.equal((await tcall("GET", "/v1/memory")).body.proposals.length, 2, "any paired device reads");
    for (const [m, p, b] of [["PATCH", `/v1/memory/${kept.id}`, { text: "Sneaky" }], ["POST", `/v1/memory/proposals/${fix!.id}/accept`, {}], ["DELETE", `/v1/memory/proposals/${extra!.id}`, undefined], ["POST", "/v1/memory", { text: "Sneaky addition" }]] as const) {
      assert.equal((await tcall(m, p, b as Record<string, unknown> | undefined)).status, 403, `${m} ${p}`);
    }
    assert.equal(memory.proposals().length, 2, "nothing changed");

    const acc = await call("POST", `/v1/memory/proposals/${fix!.id}/accept`, {});
    assert.deepEqual([acc.status, acc.body.item.id, acc.body.item.text, acc.body.item.history[0].text, acc.body.proposals.length], [200, kept.id, "My sister Mina lives in Daegu now", "My sister Mina lives in Busan", 1]);
    const ign = await call("DELETE", `/v1/memory/proposals/${extra!.id}`);
    assert.deepEqual([ign.status, ign.body.proposals.length], [200, 0]);
    assert.deepEqual(memory.propose([{ text: "I am allergic to peanuts" }], "task_2"), [], "a turned-down suggestion is not offered again");
    assert.equal((await call("POST", "/v1/memory/proposals/prop_ffffffffff/accept", {})).status, 404);

    const edit = await call("PATCH", `/v1/memory/${kept.id}`, { text: "My sister Mina lives in Ulsan", expires: null });
    assert.deepEqual([edit.status, edit.body.item.expiresAt, edit.body.item.source, edit.body.item.history.length], [200, undefined, { type: "owner" }, 2]);
    assert.equal((await call("PATCH", `/v1/memory/${kept.id}`, { text: "my password = hunter2hunter2" })).status, 400);
    assert.equal((await call("PATCH", `/v1/memory/${kept.id}`, { kind: "love" })).status, 400);
    assert.equal((await call("PATCH", "/v1/memory/mem_ffffffffff", { text: "Nothing here" })).status, 404);

    // A memory that ran out is listed as past, then removed.
    await call("PATCH", `/v1/memory/${kept.id}`, { expires: new Date(t.clock.now() + 86_400_000).toISOString().slice(0, 10) });
    t.clock.advance(3 * 86_400_000);
    const past = (await call("GET", "/v1/memory")).body;
    assert.deepEqual([past.items.length, past.past.map((i: { id: string }) => i.id)], [0, [kept.id]]);
    t.clock.advance(40 * 86_400_000);
    assert.deepEqual((await call("GET", "/v1/memory")).body.past, [], "removed after thirty days");
    await t.close();
  });

  it("what the owner wants remembered: any device reads it, the machine or an admin device changes it, and the agent sees it", async () => {
    const t = await boot();
    const call = (method: string, path: string, body?: unknown) => t.owner.call(t.base, t.clock, method, path, body);
    const empty = await call("GET", "/v1/memory");
    assert.deepEqual([empty.status, empty.body.items, empty.body.max, empty.body.textMax], [200, [], 200, 300]);
    assert.equal((await call("POST", "/v1/memory", { text: "no" })).status, 400, "too short");
    assert.equal((await call("POST", "/v1/memory", { text: "A fine sentence", kind: "secret" })).status, 400);
    assert.equal((await call("POST", "/v1/memory", { text: "my password = hunter2hunter2" })).status, 400, "a secret would go to the model with every task");
    const a = await call("POST", "/v1/memory", { text: "Give me reports as a short list", kind: "preference" });
    assert.equal(a.status, 201);
    assert.deepEqual([a.body.item.kind, a.body.item.source], ["preference", { type: "owner" }]);
    const b = await call("POST", "/v1/memory", { text: "My sister Mina lives in Busan" });
    assert.equal(b.body.item.kind, "fact", "fact is the default");
    t.clock.advance(5000);
    const c = await call("POST", "/v1/memory", { text: "Newest thing here" });
    assert.deepEqual((await call("GET", "/v1/memory")).body.items.map((i: { id: string }) => i.id), [c.body.item.id, b.body.item.id, a.body.item.id], "newest first");
    assert.ok(existsSync(join(t.cfgDir, "memory.json")));

    const ex = clientExchange(t.owner.serverKey);
    const code = ex.open((await call("POST", "/v1/pairing-codes", { epk: ex.epk, admin: false })).body.sealed, `pairing-code|${t.owner.id}`);
    const tablet = new Client("tablet");
    await tablet.pair(t.base, code);
    assert.equal((await tablet.call(t.base, t.clock, "GET", "/v1/memory")).body.items.length, 3, "any paired device reads");
    assert.equal((await tablet.call(t.base, t.clock, "POST", "/v1/memory", { text: "Sneaky addition" })).status, 403);
    assert.equal((await tablet.call(t.base, t.clock, "DELETE", `/v1/memory/${a.body.item.id}`)).status, 403);

    assert.equal((await call("DELETE", `/v1/memory/${a.body.item.id}`)).status, 200);
    assert.equal((await call("DELETE", `/v1/memory/${a.body.item.id}`)).status, 404);
    assert.equal((await call("DELETE", "/v1/memory/not-an-id")).status, 404);
    assert.equal((await call("PUT", "/v1/memory", {})).status, 405);
    assert.equal((await call("GET", "/v1/memory")).body.items.length, 2);
    await t.close();
  });

  it("a limited device can refuse what would steer later tasks (a memory, a skill) but not approve it", async () => {
    const t = await boot();
    const ex = clientExchange(t.owner.serverKey);
    const code = ex.open((await t.owner.call(t.base, t.clock, "POST", "/v1/pairing-codes", { epk: ex.epk, admin: false })).body.sealed, `pairing-code|${t.owner.id}`);
    const tablet = new Client("tablet");
    await tablet.pair(t.base, code);
    const mk = (id: string, tool: string) => t.rt.store.createApproval({ id, taskId: "t_none", actionKey: id, tool, summary: "x", risk: "external_effect", resources: [], irreversible: false, status: "pending", createdAt: t.clock.now(), expiresAt: t.clock.now() + 60_000 });
    for (const [i, tool] of ["memory.save", "skill.create", "skill.update", "memory.forget"].entries()) await mk(`appr_s${i}`, tool);
    await mk("appr_plain", "fs.write");
    const decide = (who: Client, id: string, decision: string) => who.call(t.base, t.clock, "POST", `/v1/approvals/${id}/decide`, { decision });
    for (const i of [0, 1, 2, 3]) assert.equal((await decide(tablet, `appr_s${i}`, "approved")).status, 403, "a limited device cannot approve it");
    assert.equal((await decide(tablet, "appr_s0", "denied")).status, 200, "but it can say no");
    assert.equal((await decide(t.owner, "appr_s1", "approved")).status, 200, "an admin device can approve");
    assert.equal((await decide(tablet, "appr_plain", "approved")).status, 200, "everything else is still decided from any paired device");
    await t.close();
  });

  it("stores an uploaded file inside the workspace under uploads/, with a safe name", async () => {
    const t = await boot({ maxUploadBytes: 1000 });
    const data = Buffer.from("%PDF-1.7 fake");
    const up = await t.owner.call(t.base, t.clock, "POST", `/v1/files?name=${encodeURIComponent("../../회의록 11월.pdf")}`, undefined, data);
    assert.equal(up.status, 201);
    assert.equal(up.body.path, "uploads/20261111030405-_.._회의록 11월.pdf");
    assert.deepEqual(readFileSync(join(t.workspace, up.body.path)), data);
    assert.ok(!existsSync(join(t.dir, "회의록 11월.pdf")), "nothing lands outside the workspace");
    assert.equal((await t.owner.call(t.base, t.clock, "POST", "/v1/files?name=a.txt", undefined, data)).status, 201);
    assert.equal((await t.owner.call(t.base, t.clock, "POST", "/v1/files?name=a.txt", undefined, data)).status, 409, "same second, same name: never overwritten");
    assert.equal((await t.owner.call(t.base, t.clock, "POST", "/v1/files?name=", undefined, data)).status, 400);
    assert.equal((await t.owner.call(t.base, t.clock, "POST", "/v1/files?name=big.bin", undefined, Buffer.alloc(2000, 1))).status, 413);
    const anon = await fetch(`${t.base}/v1/files?name=x.bin`, { method: "POST", body: Buffer.alloc(900, 1) });
    assert.equal(anon.status, 401);
    await t.close();
  });
});
