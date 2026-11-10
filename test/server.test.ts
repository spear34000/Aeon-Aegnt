import assert from "node:assert/strict";
import { generateKeyPairSync, randomBytes, sign, type KeyObject } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { ManualClock } from "../src/core/clock.ts";
import { MemoryStore } from "../src/core/memory-store.ts";
import { MockProvider } from "../src/core/models/mock.ts";
import { MemorySecretStore } from "../src/core/secrets.ts";
import { createRuntime } from "../src/node/runtime.ts";
import { canonicalRequest, DeviceRegistry, PairingManager } from "../src/node/server/device-auth.ts";
import { AeonServer } from "../src/node/server/server.ts";
import { finishReply, planReply, tempDir, toolReply } from "./helpers.ts";

const SECRET = "exa_server_secret_VALUE_98765";

async function rawGet(base: string, target: string): Promise<string> {
  const { connect } = await import("node:net");
  return new Promise((resolve) => {
    const s = connect(Number(new URL(base).port), "127.0.0.1", () => s.write(`GET ${target} HTTP/1.1
Host: x
Connection: close

`));
    let out = "";
    s.on("data", (c) => (out += c));
    s.on("close", () => resolve(out));
  });
}

async function boot(replies: string[] = []) {
  const { dir, cleanup } = tempDir();
  const clock = new ManualClock(Date.now());
  const model = new MockProvider("m", replies);
  const store = new MemoryStore();
  const workspace = join(dir, "ws");
  mkdirSync(workspace);
  const rt = createRuntime({ workspace, providers: ["m"], providerOverrides: [model], fallbackAllowed: false, dataConsent: [], store, clock });
  const registry = new DeviceRegistry(null);
  const pairing = new PairingManager(registry);
  const secrets = new MemorySecretStore();
  const web = join(dir, "web");
  mkdirSync(web);
  writeFileSync(join(web, "index.html"), "<!doctype html><title>Aeon</title>");
  writeFileSync(join(dir, "outside.txt"), "TOP-SECRET");
  let configChanged = 0;
  const server = new AeonServer({ getRuntime: () => rt, secrets, configDir: join(dir, "cfg"), registry, pairing, clock, webRoot: web, tickIntervalMs: 0, onConfigChanged: () => void configChanged++ });
  const { port } = await server.listen(0);
  const base = `http://127.0.0.1:${port}`;
  return {
    base, clock, rt, store, registry, pairing, secrets, model, dir, server,
    configChanged: () => configChanged,
    close: async () => {
      await server.close();
      cleanup();
    },
  };
}

class Device {
  readonly priv: KeyObject;
  readonly pubB64: string;
  id = "";
  readonly name: string;
  constructor(name = "test-phone") {
    this.name = name;
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    this.priv = privateKey;
    this.pubB64 = publicKey.export({ type: "spki", format: "der" }).toString("base64");
  }
  async pair(base: string, code: string) {
    const clientNonce = randomBytes(16).toString("base64");
    const proof = PairingManager.clientProof(PairingManager.normalize(code), this.name, this.pubB64, clientNonce);
    const res = await fetch(`${base}/v1/pair`, { method: "POST", body: JSON.stringify({ deviceName: this.name, publicKey: this.pubB64, clientNonce, proof }) });
    const body = (await res.json()) as { deviceId?: string; serverProof?: string };
    if (res.status === 201) {
      this.id = body.deviceId!;
      assert.equal(body.serverProof, PairingManager.serverProof(PairingManager.normalize(code), this.id, clientNonce, this.pubB64), "server proves it knew the code");
    }
    return { status: res.status, body };
  }
  headers(method: string, path: string, body: string, ts: number, nonce = randomBytes(12).toString("hex"), p1363 = false) {
    const data = canonicalRequest(method, path, String(ts), nonce, body);
    const signature = sign("sha256", Buffer.from(data), p1363 ? { key: this.priv, dsaEncoding: "ieee-p1363" } : this.priv).toString("base64");
    return { "x-aeon-device": this.id, "x-aeon-timestamp": String(ts), "x-aeon-nonce": nonce, "x-aeon-signature": signature };
  }
  async call(base: string, clock: ManualClock, method: string, path: string, json?: unknown, opts: { p1363?: boolean; nonce?: string; ts?: number } = {}) {
    const body = json === undefined ? "" : JSON.stringify(json);
    const h = this.headers(method, path, body, opts.ts ?? clock.now(), opts.nonce, opts.p1363);
    const res = await fetch(`${base}${path}`, { method, headers: h, ...(body ? { body } : {}) });
    const text = await res.text();
    return { status: res.status, body: text ? (JSON.parse(text) as Record<string, any>) : {} };
  }
}

async function pairedDevice(t: Awaited<ReturnType<typeof boot>>) {
  const d = new Device();
  const r = await d.pair(t.base, t.pairing.newCode(t.clock.now()).code);
  assert.equal(r.status, 201);
  return d;
}

describe("Device pairing", () => {
  it("pairs with a one-time code; the code is single-use", async () => {
    const t = await boot();
    const code = t.pairing.newCode(t.clock.now()).code;
    assert.equal((await new Device("a").pair(t.base, code)).status, 201);
    assert.equal((await new Device("b").pair(t.base, code)).status, 403, "second use of the same code fails");
    assert.equal(t.registry.list().length, 1);
    await t.close();
  });

  it("codes expire, wrong codes fail, and 5 wrong guesses burn every code and lock pairing", async () => {
    const t = await boot();
    const code = t.pairing.newCode(t.clock.now()).code;
    t.clock.advance(6 * 60_000);
    assert.equal((await new Device().pair(t.base, code)).status, 403, "expired");

    const live = t.pairing.newCode(t.clock.now()).code;
    // The expired attempt above already counted as failure #1, so the 4th wrong guess trips the lock.
    const statuses: number[] = [];
    for (let i = 0; i < 5; i++) statuses.push((await new Device().pair(t.base, "AAAAAAAAAAAAAAAAAAAA")).status);
    assert.deepEqual(statuses, [403, 403, 403, 403, 429]);
    assert.equal((await new Device().pair(t.base, live)).status, 429, "locked even for the right code");
    t.clock.advance(61_000);
    assert.equal((await new Device().pair(t.base, live)).status, 403, "the live code was burned by the guessing");
    await t.close();
  });

  it("rejects malformed pairing requests", async () => {
    const t = await boot();
    for (const body of [{}, { deviceName: "x" }, { deviceName: "x", publicKey: "not-a-key", clientNonce: "n", proof: "p" }]) {
      const res = await fetch(`${t.base}/v1/pair`, { method: "POST", body: JSON.stringify(body) });
      assert.equal(res.status, 400);
    }
    await t.close();
  });

  it("the pairing code never appears on the wire", async () => {
    const t = await boot();
    const code = t.pairing.newCode(t.clock.now()).code;
    const sent: string[] = [];
    const realFetch = globalThis.fetch;
    globalThis.fetch = (async (u: string | URL, init?: RequestInit) => (sent.push(String(init?.body ?? "")), realFetch(u, init))) as typeof fetch;
    try {
      await new Device().pair(t.base, code);
    } finally {
      globalThis.fetch = realFetch;
    }
    assert.ok(sent.length === 1 && !sent[0]!.includes(code));
    await t.close();
  });
});

describe("Request authentication", () => {
  it("unsigned, badly signed, stale, replayed and revoked requests are all rejected with a vague 401", async () => {
    const t = await boot();
    const d = await pairedDevice(t);

    assert.equal((await fetch(`${t.base}/v1/status`)).status, 401, "no signature");
    assert.equal((await d.call(t.base, t.clock, "GET", "/v1/status")).status, 200);

    const other = new Device("evil");
    other.id = d.id; // someone else's key claiming this device id
    assert.equal((await other.call(t.base, t.clock, "GET", "/v1/status")).status, 401);

    assert.equal((await d.call(t.base, t.clock, "GET", "/v1/status", undefined, { ts: t.clock.now() - 120_000 })).status, 401, "stale");
    assert.equal((await d.call(t.base, t.clock, "GET", "/v1/status", undefined, { ts: t.clock.now() + 120_000 })).status, 401, "from the future");

    const first = await d.call(t.base, t.clock, "GET", "/v1/status", undefined, { nonce: "fixed-nonce-0001" });
    const replay = await d.call(t.base, t.clock, "GET", "/v1/status", undefined, { nonce: "fixed-nonce-0001" });
    assert.deepEqual([first.status, replay.status], [200, 401], "same nonce twice");

    t.registry.revoke(d.id);
    const after = await d.call(t.base, t.clock, "GET", "/v1/status");
    assert.equal(after.status, 401);
    assert.equal(after.body.error, "unauthorized", "no hint about which check failed");
    await t.close();
  });

  it("the signature covers method, path+query and body: tampering is detected", async () => {
    const t = await boot();
    const d = await pairedDevice(t);
    const body = JSON.stringify({ goal: "benign" });
    const h = d.headers("POST", "/v1/tasks", body, t.clock.now());
    const tamperedBody = await fetch(`${t.base}/v1/tasks`, { method: "POST", headers: h, body: JSON.stringify({ goal: "malicious" }) });
    assert.equal(tamperedBody.status, 401);
    const h2 = d.headers("GET", "/v1/approvals?status=pending", "", t.clock.now());
    const tamperedQuery = await fetch(`${t.base}/v1/approvals?status=denied`, { headers: h2 });
    assert.equal(tamperedQuery.status, 401);
    const h3 = d.headers("GET", "/v1/status", "", t.clock.now());
    assert.equal((await fetch(`${t.base}/v1/tasks`, { headers: h3 })).status, 401, "signature for one path is useless on another");
    await t.close();
  });

  it("accepts WebCrypto-style (IEEE P1363) signatures as well as DER", async () => {
    const t = await boot();
    const d = await pairedDevice(t);
    assert.equal((await d.call(t.base, t.clock, "GET", "/v1/status", undefined, { p1363: true })).status, 200);
    await t.close();
  });

  it("rejects oversized bodies", async () => {
    const t = await boot();
    const d = await pairedDevice(t);
    const big = { goal: "x".repeat(300_000) };
    const r = await d.call(t.base, t.clock, "POST", "/v1/tasks", big);
    assert.equal(r.status, 413);
    await t.close();
  });
});

describe("Tasks, approvals and events through the API", () => {
  it("creates a task, the server runs it, and the result is verified against the real filesystem", async () => {
    const t = await boot([planReply(), toolReply("fs.write", { path: "out.txt", content: "from the API" }), finishReply("wrote out.txt")]);
    const d = await pairedDevice(t);
    const created = await d.call(t.base, t.clock, "POST", "/v1/tasks", { goal: "write out.txt", criteria: [{ kind: "file_contains", path: "out.txt", text: "from the API" }] });
    assert.equal(created.status, 201);
    await t.server.tick();
    const got = await d.call(t.base, t.clock, "GET", `/v1/tasks/${created.body.task.id}`);
    assert.equal(got.body.task.status, "COMPLETED");
    assert.equal(got.body.task.verification.status, "verified");
    assert.equal(got.body.task.steps[0].tool, "fs.write");
    const list = await d.call(t.base, t.clock, "GET", "/v1/tasks");
    assert.equal(list.body.tasks.length, 1);
    assert.equal(list.body.tasks[0].steps, undefined, "list endpoint stays small");
    await t.close();
  });

  it("validates task input", async () => {
    const t = await boot();
    const d = await pairedDevice(t);
    for (const body of [{}, { goal: "" }, { goal: 5 }, { goal: "x", criteria: [{ kind: "rm_rf" }] }, { goal: "x", runAfter: "soon" }]) {
      assert.equal((await d.call(t.base, t.clock, "POST", "/v1/tasks", body)).status, 400, JSON.stringify(body));
    }
    assert.equal((await d.call(t.base, t.clock, "GET", "/v1/tasks/task_doesnotexist")).status, 404);
    // fetch() would normalise dot segments client-side, so send the raw request target over a socket.
    assert.match(await rawGet(t.base, "/v1/tasks/../../etc/passwd"), /^HTTP\/1\.1 400/);
    assert.match(await rawGet(t.base, "/v1/tasks/%2e%2e/%2e%2e/etc/passwd"), /^HTTP\/1\.1 400/);
    await t.close();
  });

  it("a dangerous action waits for approval that a phone can grant over the API", async () => {
    const t = await boot([planReply(), toolReply("fs.delete", { path: "old.txt" }), finishReply("deleted")]);
    writeFileSync(join(t.rt.guard.root, "old.txt"), "x");
    const d = await pairedDevice(t);
    const created = await d.call(t.base, t.clock, "POST", "/v1/tasks", { goal: "delete old.txt" });
    await t.server.tick();
    const pending = await d.call(t.base, t.clock, "GET", "/v1/approvals?status=pending");
    assert.equal(pending.body.approvals.length, 1);
    assert.match(pending.body.approvals[0].summary, /DELETE file/);
    assert.equal((await d.call(t.base, t.clock, "GET", `/v1/tasks/${created.body.task.id}`)).body.task.status, "WAITING_FOR_APPROVAL");

    const bad = await d.call(t.base, t.clock, "POST", `/v1/approvals/${pending.body.approvals[0].id}/decide`, { decision: "maybe" });
    assert.equal(bad.status, 400);
    const ok = await d.call(t.base, t.clock, "POST", `/v1/approvals/${pending.body.approvals[0].id}/decide`, { decision: "approved" });
    assert.equal(ok.status, 200);
    await t.server.tick();
    assert.equal((await d.call(t.base, t.clock, "GET", `/v1/tasks/${created.body.task.id}`)).body.task.status, "COMPLETED");
    const again = await d.call(t.base, t.clock, "POST", `/v1/approvals/${pending.body.approvals[0].id}/decide`, { decision: "denied" });
    assert.equal(again.status, 409, "an approval can only be decided once");
    await t.close();
  });

  it("streams audit events (SSE) and resumes after a given id", async () => {
    const t = await boot([planReply(), finishReply("ok")]);
    const d = await pairedDevice(t);
    await d.call(t.base, t.clock, "POST", "/v1/tasks", { goal: "stream me" });
    await t.server.tick();

    const ac = new AbortController();
    const h = d.headers("GET", "/v1/events", "", t.clock.now());
    const res = await fetch(`${t.base}/v1/events`, { headers: h, signal: ac.signal });
    assert.equal(res.status, 200);
    assert.match(res.headers.get("content-type") ?? "", /text\/event-stream/);
    const reader = res.body!.getReader();
    let text = "";
    const deadline = Date.now() + 3000;
    while (!text.includes("task_state") && Date.now() < deadline) {
      const { value, done } = await reader.read();
      if (done) break;
      text += new TextDecoder().decode(value);
    }
    ac.abort();
    assert.match(text, /event: audit/);
    assert.match(text, /task_submitted/);
    assert.match(text, /task_state/);
    await t.close();
  });
});

describe("Settings and secrets over the API", () => {
  it("validates config updates, persists them and notifies the runtime", async () => {
    const t = await boot();
    const d = await pairedDevice(t);
    const bad = await d.call(t.base, t.clock, "PUT", "/v1/config", { providers: ["skynet"] });
    assert.equal(bad.status, 400);
    assert.equal((await d.call(t.base, t.clock, "PUT", "/v1/config", { apiKey: "sk-123456" })).status, 400, "no secret field in config");
    const ok = await d.call(t.base, t.clock, "PUT", "/v1/config", { providers: ["ollama"], dataConsent: [], fallbackAllowed: false, ollamaModel: "m" });
    assert.equal(ok.status, 200);
    assert.equal((await d.call(t.base, t.clock, "GET", "/v1/config")).body.config.ollamaModel, "m");
    assert.equal(t.configChanged(), 1);
    await t.close();
  });

  it("secrets are write-only: stored, listed by name, never returned", async () => {
    const t = await boot();
    const d = await pairedDevice(t);
    assert.equal((await d.call(t.base, t.clock, "PUT", "/v1/secrets/EXAONE_API_KEY", { value: SECRET })).status, 204);
    assert.equal(await t.secrets.get("EXAONE_API_KEY"), SECRET);
    const list = await d.call(t.base, t.clock, "GET", "/v1/secrets");
    assert.deepEqual(list.body.names, ["EXAONE_API_KEY"]);
    assert.ok(!JSON.stringify(list.body).includes(SECRET));
    for (const p of ["/v1/status", "/v1/config", "/v1/tasks", "/v1/devices"]) assert.ok(!JSON.stringify((await d.call(t.base, t.clock, "GET", p)).body).includes(SECRET));
    assert.equal((await d.call(t.base, t.clock, "PUT", "/v1/secrets/lowercase", { value: "x" })).status, 404, "bad names never reach the store");
    assert.equal((await d.call(t.base, t.clock, "DELETE", "/v1/secrets/EXAONE_API_KEY")).status, 204);
    assert.equal(await t.secrets.get("EXAONE_API_KEY"), null);
    await t.close();
  });

  it("devices can be listed (without keys) and revoked", async () => {
    const t = await boot();
    const d = await pairedDevice(t);
    const list = await d.call(t.base, t.clock, "GET", "/v1/devices");
    assert.equal(list.body.devices[0].publicKey, undefined);
    const other = await pairedDevice(t);
    assert.equal((await d.call(t.base, t.clock, "DELETE", `/v1/devices/${other.id}`)).status, 204);
    assert.equal((await other.call(t.base, t.clock, "GET", "/v1/status")).status, 401);
    await t.close();
  });
});

describe("Static web UI hosting", () => {
  it("serves the UI with security headers, falls back to index.html, and cannot escape the web root", async () => {
    const t = await boot();
    const home = await fetch(`${t.base}/`);
    assert.equal(home.status, 200);
    assert.match(home.headers.get("content-security-policy") ?? "", /frame-ancestors 'none'/);
    assert.equal(home.headers.get("x-frame-options"), "DENY");
    assert.equal((await fetch(`${t.base}/some/client/route`)).status, 200, "SPA fallback");
    for (const evil of ["/..%2foutside.txt", "/%2e%2e/outside.txt", "/..%5coutside.txt", "/%2e%2e%2f%2e%2e%2foutside.txt"]) {
      const r = await fetch(`${t.base}${evil}`);
      assert.ok(!(await r.text()).includes("TOP-SECRET"), `leaked via ${evil}`);
    }
    const raw = await new Promise<string>((resolve) => {
      import("node:net").then(({ connect }) => {
        const s = connect(Number(new URL(t.base).port), "127.0.0.1", () => s.write("GET /../outside.txt HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"));
        let out = "";
        s.on("data", (c) => (out += c));
        s.on("close", () => resolve(out));
      });
    });
    assert.ok(!raw.includes("TOP-SECRET"), "raw dot-dot request");
    await t.close();
  });

  it("the unauthenticated surface is only hello, pair and static files", async () => {
    const t = await boot();
    const hello = (await (await fetch(`${t.base}/v1/hello`)).json()) as Record<string, unknown>;
    assert.deepEqual(Object.keys(hello).sort(), ["api", "name", "pairingRequired"]);
    for (const p of ["/v1/tasks", "/v1/approvals", "/v1/usage", "/v1/config", "/v1/secrets", "/v1/devices", "/v1/events", "/v1/status"]) {
      assert.equal((await fetch(`${t.base}${p}`)).status, 401, p);
    }
    assert.equal((await fetch(`${t.base}/v1/nope`)).status, 401);
    await t.close();
  });
});
