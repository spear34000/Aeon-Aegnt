import assert from "node:assert/strict";
import { generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, it } from "node:test";
import { inflateSync } from "node:zlib";
import { ManualClock } from "../src/core/clock.ts";
import { DEFAULT_CONFIG, parseConfig } from "../src/core/config.ts";
import { brandCandidates, builtinConnections, iconLinks, prettyName, registryConnection, siteDomain, skillConnection, logoFromSvg } from "../src/core/connections.ts";
import { MemoryStore } from "../src/core/memory-store.ts";
import { MockProvider } from "../src/core/models/mock.ts";
import { MemorySecretStore } from "../src/core/secrets.ts";
import { ConnectionService, normaliseImage, type Fetched } from "../src/node/connections.ts";
import { createRuntime } from "../src/node/runtime.ts";
import { canonicalRequest, DeviceRegistry, PairingManager } from "../src/node/server/device-auth.ts";
import { AeonServer } from "../src/node/server/server.ts";
import { closeAfterTest, closeLeftovers, tempDir } from "./helpers.ts";

afterEach(closeLeftovers);

// Shapes copied from the official MCP registry (registry.modelcontextprotocol.io, 2026-11-11), trimmed.
const BROWSER_USE = {
  server: {
    name: "com.browser-use/browser-use",
    description: "Browser agents and stealth cloud browsers for AI: run web tasks, fill forms, extract data.",
    repository: { url: "https://github.com/browser-use/browser-use", source: "github" },
    version: "0.13.11",
    websiteUrl: "https://browser-use.com",
    packages: [{ registryType: "pypi", identifier: "browser-use", transport: { type: "stdio" } }],
    remotes: [{ type: "streamable-http", url: "https://api.browser-use.com/v3/mcp", headers: [{ name: "X-Browser-Use-API-Key", isRequired: true, isSecret: true }] }],
  },
  _meta: { "io.modelcontextprotocol.registry/official": { status: "active", isLatest: true } },
};
const NAVER_MAIL = {
  server: { name: "io.github.CapyKkuing/naver-mail-mcp", title: "Naver Mail (네이버 메일)", description: "Read and search Naver Mail.", version: "1.0.0", remotes: [{ type: "streamable-http", url: "https://naver-mail.example/mcp" }] },
};
const KAKAO = {
  server: { name: "io.github.groove79/mcp-kakao", title: "KakaoTalk Message", description: "Send KakaoTalk messages.", version: "0.2.0", packages: [{ registryType: "npm", identifier: "mcp-kakao", environmentVariables: [{ name: "KAKAO_REST_KEY", isRequired: true }] }] },
};
const NOTION = { server: { name: "com.notion/mcp", description: "Official Notion MCP server", version: "1.0.1", remotes: [{ type: "sse", url: "https://mcp.notion.com/sse" }, { type: "streamable-http", url: "https://mcp.notion.com/mcp" }] } };

const PNG_1PX = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");

/** A 2x2 .ico whose 32-bit pixels carry no alpha: opacity comes from the AND mask (top-left pixel is transparent). */
function ico2x2(): Buffer {
  const dib = Buffer.alloc(40 + 16 + 8);
  dib.writeUInt32LE(40, 0);
  dib.writeInt32LE(2, 4);
  dib.writeInt32LE(4, 8); // height is doubled: image + mask
  dib.writeUInt16LE(1, 12);
  dib.writeUInt16LE(32, 14);
  // bottom row first: (0,1) blue, (1,1) green; then top row: (0,0) red, (1,0) white. Stored as B,G,R,A.
  Buffer.from([255, 0, 0, 0, 0, 255, 0, 0, 0, 0, 255, 0, 255, 255, 255, 0]).copy(dib, 40);
  dib[40 + 16] = 0b0000_0000; // bottom mask row: both opaque
  dib[40 + 16 + 4] = 0b1000_0000; // top mask row: first pixel transparent
  const head = Buffer.alloc(22);
  head.writeUInt16LE(1, 2);
  head.writeUInt16LE(1, 4);
  head[6] = 2;
  head[7] = 2;
  head.writeUInt16LE(1, 10);
  head.writeUInt16LE(32, 12);
  head.writeUInt32LE(dib.length, 14);
  head.writeUInt32LE(22, 18);
  return Buffer.concat([head, dib]);
}

function pngPixels(png: Uint8Array): { w: number; h: number; rgba: number[] } {
  const b = Buffer.from(png);
  const w = b.readUInt32BE(16);
  const h = b.readUInt32BE(20);
  const idat = b.subarray(b.indexOf("IDAT") + 4, b.indexOf("IEND") - 8);
  const raw = inflateSync(idat);
  const rgba: number[] = [];
  for (let y = 0; y < h; y++) rgba.push(...raw.subarray(y * (w * 4 + 1) + 1, (y + 1) * (w * 4 + 1)));
  return { w, h, rgba };
}

describe("Connection mapping (pure)", () => {
  it("maps registry entries: a hosted endpoint is an integration, a package-only server is an MCP server", () => {
    const b = registryConnection(BROWSER_USE)!;
    assert.deepEqual(
      { id: b.connection.id, kind: b.connection.kind, state: b.connection.state, title: b.connection.title, publisher: b.connection.publisher, requires: b.connection.requires, remoteUrl: b.connection.remoteUrl },
      { id: "mcp:com.browser-use/browser-use", kind: "integration", state: "available", title: "Browser Use", publisher: "browser-use.com", requires: ["X-Browser-Use-API-Key"], remoteUrl: "https://api.browser-use.com/v3/mcp" },
    );
    assert.deepEqual(b.plan, { title: "Browser Use", urls: [], host: "browser-use.com", githubOwner: "browser-use" });

    const k = registryConnection(KAKAO)!;
    assert.equal(k.connection.kind, "mcp");
    assert.equal(k.connection.runtime, "npm");
    assert.deepEqual(k.connection.requires, ["KAKAO_REST_KEY"]);
    assert.equal(k.connection.publisher, "github.com/groove79");
    assert.equal(k.plan.host, null, "a GitHub namespace has no site of its own to take an icon from");
    assert.equal(k.plan.githubOwner, "groove79");

    const n = registryConnection(NOTION)!;
    assert.equal(n.connection.title, "Notion", "a generic local name falls back to the namespace");
    assert.equal(n.connection.remoteUrl, "https://mcp.notion.com/mcp", "streamable HTTP is preferred over SSE");
  });

  it("drops entries it cannot use", () => {
    assert.equal(registryConnection({ server: { name: "com.x/y", description: "no transport" } }), null);
    assert.equal(registryConnection({ server: { name: "com.x/y", remotes: [{ type: "streamable-http", url: "http://plain.example/mcp" }] } }), null, "plain http is not offered");
    assert.equal(registryConnection({ ...BROWSER_USE, _meta: { "io.modelcontextprotocol.registry/official": { status: "deleted" } } }), null);
    assert.equal(registryConnection({ server: { name: "../../etc", remotes: [{ url: "https://a.example" }] } }), null);
    assert.equal(registryConnection("nonsense"), null);
  });

  it("derives names, brand candidates, site domains and page icons without a lookup table", () => {
    assert.equal(prettyName("io.github.acme/naver-mail-mcp"), "Naver Mail");
    assert.equal(prettyName("io.github.github/github-mcp-server"), "Github");
    assert.deepEqual(brandCandidates("Naver Mail (네이버 메일)"), ["navermail", "naver"]);
    assert.deepEqual(brandCandidates("Google Calendar MCP"), ["googlecalendar", "google"]);
    assert.deepEqual(brandCandidates("다나와"), [], "a title without latin letters has no brand candidate");
    assert.equal(siteDomain("mcp.shop.danawa.com"), "danawa.com");
    assert.equal(siteDomain("api.example.co.kr"), "example.co.kr");
    const html = `<link rel="shortcut icon" href="//img.danawa.com/favicon.ico" /><link rel="icon" href="/a.svg" type="image/svg+xml"><link rel="icon" href="/favicon-32x32.png" sizes="32x32"><link rel='apple-touch-icon' href='/apple-touch-icon.png'><link rel="stylesheet" href="/x.css">`;
    assert.deepEqual(iconLinks(html, "https://www.danawa.com/"), ["https://www.danawa.com/apple-touch-icon.png", "https://www.danawa.com/favicon-32x32.png", "https://img.danawa.com/favicon.ico"]);
  });

  it("groups built-in tools by namespace and reads skill front matter", () => {
    const g = builtinConnections([{ name: "fs.read", description: "Read a file." }, { name: "fs.write", description: "Write a file." }, { name: "http.get", description: "GET a URL." }]);
    assert.deepEqual(g.map((c) => [c.id, c.tools]), [["builtin:fs", ["fs.read", "fs.write"]], ["builtin:http", ["http.get"]]]);
    const s = skillConnection("meeting-notes", "---\nname: 회의록 정리\ndescription: \"녹취를 결정 사항으로 정리\"\n---\n# body");
    assert.deepEqual([s.id, s.kind, s.state, s.title, s.description], ["skill:meeting-notes", "skill", "connected", "회의록 정리", "녹취를 결정 사항으로 정리"]);
    assert.equal(skillConnection("plain", "no front matter").title, "plain");
  });

  it("config accepts only https MCP servers with unique names and no secrets", () => {
    const ok = parseConfig({ ...DEFAULT_CONFIG, mcpServers: [{ name: "com.notion/mcp", title: "Notion", url: "https://mcp.notion.com/mcp", requires: ["Authorization"] }] });
    assert.equal(ok.mcpServers?.[0]?.url, "https://mcp.notion.com/mcp");
    const bad = (s: unknown) => assert.throws(() => parseConfig({ ...DEFAULT_CONFIG, mcpServers: [s] }));
    bad({ name: "a", url: "http://plain.example/mcp" });
    bad({ name: "a", url: "https://user:pw@host.example/mcp" });
    bad({ name: "a", url: "https://a.example", token: "secret" });
    bad({ name: "bad name", url: "https://a.example" });
    assert.throws(() => parseConfig({ ...DEFAULT_CONFIG, mcpServers: [{ name: "a", url: "https://a.example" }, { name: "a", url: "https://b.example" }] }), /duplicate/);
  });
});

describe("Icon normalisation", () => {
  it("passes PNG through and converts a bitmap .ico to PNG using its mask", () => {
    assert.equal(normaliseImage(PNG_1PX)?.mime, "image/png");
    const out = normaliseImage(ico2x2())!;
    assert.equal(out.mime, "image/png");
    const { w, h, rgba } = pngPixels(out.bytes);
    assert.deepEqual([w, h], [2, 2]);
    assert.deepEqual(rgba, [255, 0, 0, 0, /* red, masked out */ 255, 255, 255, 255, /* white */ 0, 0, 255, 255, /* blue */ 0, 255, 0, 255 /* green */]);
  });

  it("rejects SVG, HTML and truncated data", () => {
    assert.equal(normaliseImage(Buffer.from("<svg xmlns='http://www.w3.org/2000/svg'/>")), null);
    assert.equal(normaliseImage(Buffer.from("<!doctype html>")), null);
    assert.equal(normaliseImage(ico2x2().subarray(0, 40)), null);
  });
});

function fakeNet(routes: Record<string, string | Buffer | number>) {
  const calls: string[] = [];
  const fetcher = async (url: string): Promise<Fetched> => {
    calls.push(url);
    const hit = Object.keys(routes).find((k) => url.startsWith(k));
    const v = hit === undefined ? 404 : routes[hit]!;
    if (typeof v === "number") return { status: v, text: "", bytes: new Uint8Array(), truncated: false };
    const bytes = typeof v === "string" ? Buffer.from(v) : v;
    return { status: 200, text: bytes.toString("utf8"), bytes, truncated: false };
  };
  return { calls, fetcher };
}

const REG = "https://registry.test";
const BRANDS = "https://brands.test";
const NAVER_SVG = `<svg role="img" viewBox="0 0 24 24"><title>Naver</title><path d="M16.273 12.845 7.376 0H0v24h7.726V11.156L16.624 24H24V0h-7.727v12.845Z"/></svg>`;

function service(routes: Record<string, string | Buffer | number>) {
  const { dir, cleanup } = tempDir();
  const net = fakeNet(routes);
  let now = 1_000_000;
  const svc = new ConnectionService({ configDir: dir, fetcher: net.fetcher, registryUrl: REG, brandIconsUrl: BRANDS, now: () => now });
  return { svc, dir, cleanup, calls: net.calls, advance: (ms: number) => (now += ms) };
}

describe("ConnectionService", () => {
  it("lists what is really there: built-in tools, servers in config and skill folders", () => {
    const t = service({});
    mkdirSync(join(t.dir, "skills", "weekly-report"), { recursive: true });
    writeFileSync(join(t.dir, "skills", "weekly-report", "SKILL.md"), "---\nname: 주간 보고 초안\ndescription: 작업 기록으로 보고서 초안 작성\n---\n");
    mkdirSync(join(t.dir, "skills", "not-a-skill"));
    const cfg = parseConfig({ ...DEFAULT_CONFIG, mcpServers: [{ name: "danawa", title: "다나와", url: "https://mcp.danawa.example/mcp" }] });
    const list = t.svc.installed([{ name: "fs.read", description: "Read a file." }], cfg);
    assert.deepEqual(list.map((c) => [c.id, c.kind, c.state, c.title]), [
      ["builtin:fs", "builtin", "connected", "fs"],
      ["mcp:danawa", "integration", "added", "다나와"],
      ["skill:weekly-report", "skill", "connected", "주간 보고 초안"],
    ]);
    assert.match(list[1]!.icon!, /^\/v1\/icons\/[a-f0-9]{24}$/);
    assert.equal(list[0]!.icon, null);
    t.cleanup();
  });

  it("searches the registry live, ranks the brand's own server first, marks added entries and caches for five minutes", async () => {
    const t = service({ [`${REG}/v0/servers`]: JSON.stringify({ servers: [NAVER_MAIL, KAKAO, { server: { name: "com.naver/mcp", description: "by the brand", remotes: [{ type: "streamable-http", url: "https://mcp.naver.example/mcp" }] } }, NAVER_MAIL], metadata: { nextCursor: "next:1" } }) });
    const cfg = parseConfig({ ...DEFAULT_CONFIG, mcpServers: [{ name: "io.github.groove79/mcp-kakao", url: "https://k.example/mcp" }] });
    const r = await t.svc.search("naver", cfg);
    assert.deepEqual(r.connections.map((c) => c.id), ["mcp:com.naver/mcp", "mcp:io.github.CapyKkuing/naver-mail-mcp", "mcp:io.github.groove79/mcp-kakao"], "deduplicated and ranked");
    assert.equal(r.connections[2]!.state, "added");
    assert.equal(r.nextCursor, "next:1");
    assert.match(t.calls[0]!, /search=naver/);
    await t.svc.search("  browser use ", cfg);
    assert.match(t.calls[1]!, /search=browser-use/, "spaces become the hyphens that registry names use");
    t.calls.length = 1;
    await t.svc.search("naver", cfg);
    assert.equal(t.calls.length, 1, "served from the cache");
    t.advance(5 * 60_000 + 1);
    await t.svc.search("naver", cfg);
    assert.equal(t.calls.length, 2);
    t.cleanup();
  });

  it("fails loudly when the registry is down instead of inventing entries", async () => {
    const t = service({ [`${REG}/v0/servers`]: 503 });
    await assert.rejects(t.svc.search("naver", DEFAULT_CONFIG));
    t.cleanup();
  });

  it("resolves a brand mark from the title, and caches the answer on disk", async () => {
    const t = service({
      [`${REG}/v0/servers`]: JSON.stringify({ servers: [NAVER_MAIL] }),
      [`${BRANDS}/data/simple-icons.json`]: JSON.stringify([{ title: "Naver", hex: "03C75A" }, { title: ".NET", hex: "512BD4", slug: "dotnet" }]),
      [`${BRANDS}/icons/naver.svg`]: NAVER_SVG,
    });
    const [c] = (await t.svc.search("naver", DEFAULT_CONFIG)).connections;
    const key = c!.icon!.split("/").pop()!;
    const icon = await t.svc.icon(key);
    assert.deepEqual(icon, { kind: "path", d: "M16.273 12.845 7.376 0H0v24h7.726V11.156L16.624 24H24V0h-7.727v12.845Z", color: "#03C75A" });
    const before = t.calls.length;
    assert.deepEqual(await t.svc.icon(key), icon);
    assert.equal(t.calls.length, before, "second lookup does not touch the network");
    t.cleanup();
  });

  it("falls back to the publisher's own site icon (converting .ico), then to its GitHub avatar", async () => {
    const t = service({
      [`${REG}/v0/servers`]: JSON.stringify({ servers: [BROWSER_USE, KAKAO] }),
      [`${BRANDS}/data/simple-icons.json`]: "[]",
      "https://browser-use.com/favicon.ico": ico2x2(),
      "https://browser-use.com/": `<html><head><link rel="icon" href="/favicon.ico"></head></html>`,
      "https://github.com/groove79.png": PNG_1PX,
    });
    const list = (await t.svc.search("", DEFAULT_CONFIG)).connections;
    const site = await t.svc.icon(list.find((c) => c.title === "Browser Use")!.icon!.split("/").pop()!);
    assert.equal(site?.kind, "image");
    assert.equal(site?.kind === "image" && site.mime, "image/png");
    const avatar = await t.svc.icon(list.find((c) => c.title === "KakaoTalk Message")!.icon!.split("/").pop()!);
    assert.equal(avatar?.kind === "image" && avatar.data, PNG_1PX.toString("base64"));
    t.cleanup();
  });

  it("an unknown key or a publisher with no icon anywhere yields null, and the miss is remembered for a day", async () => {
    const t = service({ [`${REG}/v0/servers`]: JSON.stringify({ servers: [NOTION] }), [`${BRANDS}/data/simple-icons.json`]: "[]" });
    assert.equal(await t.svc.icon("0".repeat(24)), null);
    const key = (await t.svc.search("notion", DEFAULT_CONFIG)).connections[0]!.icon!.split("/").pop()!;
    assert.equal(await t.svc.icon(key), null);
    const before = t.calls.length;
    assert.equal(await t.svc.icon(key), null);
    assert.equal(t.calls.length, before);
    t.advance(24 * 3600_000 + 1);
    await t.svc.icon(key);
    assert.ok(t.calls.length > before, "tried again after a day");
    t.cleanup();
  });

  it("a collection is a list of search terms; its entries come from the registry", async () => {
    const t = service({ [`${REG}/v0/servers`]: JSON.stringify({ servers: [NAVER_MAIL] }) });
    writeFileSync(join(t.dir, "collections.json"), JSON.stringify([{ id: "korea", title: { ko: "한국 서비스" }, queries: ["naver", "kakao"] }, { id: "BAD ID", title: {}, queries: [] }]));
    assert.deepEqual(t.svc.collections().map((c) => c.id), ["korea"]);
    assert.deepEqual((await t.svc.collection("korea", DEFAULT_CONFIG))!.map((c) => c.title), ["Naver Mail (네이버 메일)"]);
    assert.equal(await t.svc.collection("nope", DEFAULT_CONFIG), null);
    t.cleanup();
  });

  it("ships a default collections file that parses", () => {
    const t = service({});
    const ids = t.svc.collections().map((c) => c.id);
    assert.ok(ids.includes("korea") && ids.includes("dev"));
    t.cleanup();
  });
});

describe("Connections over the API", () => {
  async function boot(isLocal: boolean, routes: Record<string, string | Buffer | number>, admin = true) {
    const t = service(routes);
    const clock = new ManualClock(Date.now());
    const ws = join(t.dir, "ws");
    mkdirSync(ws);
    const rt = createRuntime({ workspace: ws, providers: ["m"], providerOverrides: [new MockProvider("m", [])], fallbackAllowed: false, dataConsent: [], store: new MemoryStore(), clock });
    const registry = new DeviceRegistry(null);
    const pairing = new PairingManager(registry);
    const server = new AeonServer({ getRuntime: () => rt, secrets: new MemorySecretStore(), connections: t.svc, configDir: t.dir, registry, pairing, clock, tickIntervalMs: 0, isLocalRequest: () => isLocal });
    const base = `http://127.0.0.1:${(await server.listen(0)).port}`;
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const pub = publicKey.export({ type: "spki", format: "der" }).toString("base64");
    const code = pairing.newCode(clock.now(), admin).code;
    const clientNonce = randomBytes(16).toString("base64");
    const proof = PairingManager.clientProof(PairingManager.normalize(code), "phone", pub, clientNonce);
    const paired = (await (await fetch(`${base}/v1/pair`, { method: "POST", body: JSON.stringify({ deviceName: "phone", publicKey: pub, clientNonce, proof }) })).json()) as { deviceId: string };
    const call = async (method: string, path: string, json?: unknown) => {
      const body = json === undefined ? "" : JSON.stringify(json);
      const ts = String(clock.now());
      const nonce = randomBytes(12).toString("hex");
      const signature = sign("sha256", Buffer.from(canonicalRequest(method, path, ts, nonce, body)), privateKey).toString("base64");
      const res = await fetch(`${base}${path}`, { method, headers: { "x-aeon-device": paired.deviceId, "x-aeon-timestamp": ts, "x-aeon-nonce": nonce, "x-aeon-signature": signature }, ...(body ? { body } : {}) });
      const text = await res.text();
      return { status: res.status, body: text ? (JSON.parse(text) as Record<string, any>) : {} };
    };
    return { call, base, close: closeAfterTest(async () => (await server.close(), t.cleanup())) };
  }

  it("serves installed connections, the live catalog, collections and icons to a paired device", async () => {
    const t = await boot(true, {
      [`${REG}/v0/servers`]: JSON.stringify({ servers: [NAVER_MAIL] }),
      [`${BRANDS}/data/simple-icons.json`]: JSON.stringify([{ title: "Naver", hex: "03C75A" }]),
      [`${BRANDS}/icons/naver.svg`]: NAVER_SVG,
    });
    const installed = await t.call("GET", "/v1/connections");
    assert.equal(installed.status, 200);
    assert.deepEqual(installed.body.connections.map((c: { id: string }) => c.id), ["builtin:fs", "builtin:http", "builtin:sandbox"], "exactly the tools registered in the runtime");
    assert.equal(installed.body.canEdit, true);
    const cat = await t.call("GET", "/v1/catalog?q=naver");
    assert.equal(cat.body.connections[0].title, "Naver Mail (네이버 메일)");
    assert.equal((await t.call("GET", cat.body.connections[0].icon)).body.icon.color, "#03C75A");
    assert.ok((await t.call("GET", "/v1/catalog/collections")).body.collections.length > 0);
    assert.equal((await t.call("GET", "/v1/catalog?collection=nope")).status, 404);
    assert.equal((await t.call("GET", `/v1/icons/${"0".repeat(24)}`)).status, 404);
    assert.equal((await fetch(`${t.base}/v1/connections`)).status, 401, "not available without a signature");
    await t.close();
  });

  it("with internet lookups declined the catalogue is empty and no icon is fetched", async () => {
    const t = await boot(true, { [`${REG}/v0/servers`]: JSON.stringify({ servers: [NAVER_MAIL] }) });
    const icon = (await t.call("GET", "/v1/catalog?q=naver")).body.connections[0].icon as string;
    assert.equal((await t.call("PUT", "/v1/config", { ...DEFAULT_CONFIG, onlineLookups: false })).status, 200);
    const cat = await t.call("GET", "/v1/catalog?q=naver");
    assert.deepEqual([cat.status, cat.body.connections, cat.body.disabled], [200, [], true]);
    assert.equal((await t.call("GET", icon)).status, 404);
    await t.close();
  });

  it("reports a registry outage as 502, and a device limited to chat cannot add a server", async () => {
    const t = await boot(false, { [`${REG}/v0/servers`]: 500 }, false);
    assert.equal((await t.call("GET", "/v1/catalog?q=naver")).status, 502);
    assert.equal((await t.call("GET", "/v1/connections")).body.canEdit, false);
    const add = await t.call("PUT", "/v1/config", { ...DEFAULT_CONFIG, mcpServers: [{ name: "evil", url: "https://attacker.example/mcp" }] });
    assert.equal(add.status, 403);
    assert.match(add.body.error, /mcpServers/);
    await t.close();
  });

  it("a remote device the owner trusts with settings can add and remove a server", async () => {
    const t = await boot(false, {});
    assert.equal((await t.call("GET", "/v1/connections")).body.canEdit, true);
    const add = await t.call("PUT", "/v1/config", { ...DEFAULT_CONFIG, mcpServers: [{ name: "com.notion/mcp", title: "Notion", url: "https://mcp.notion.com/mcp" }] });
    assert.equal(add.status, 200);
    assert.deepEqual((await t.call("GET", "/v1/connections")).body.connections.filter((c: { kind: string }) => c.kind === "integration").map((c: { title: string }) => c.title), ["Notion"]);
    await t.close();
  });
});

const LOGO = `<svg width="24" height="24" viewBox="0 0 40 40" xmlns="http://www.w3.org/2000/svg">\n<path d="M26.9 9.9H22.1L30.8 31.8H35.5L26.9 9.9Z" fill="currentColor"/>\n<path d="M13 9.9L4.5 31.8H9.3L13 9.9Z" fill="currentColor"/>\n</svg>`;
const PLACEHOLDER = `<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">\n  <path shape-rendering="geometricPrecision" d="M9.8 15.9L9 18.7L8.2 15.9Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>\n</svg>`;

describe("provider logos", () => {
  it("reads filled paths on a square canvas and refuses everything it cannot draw faithfully", () => {
    assert.deepEqual(logoFromSvg(LOGO), { d: "M26.9 9.9H22.1L30.8 31.8H35.5L26.9 9.9Z M13 9.9L4.5 31.8H9.3L13 9.9Z", size: 40 });
    assert.deepEqual(logoFromSvg(LOGO.replace('fill="currentColor"', 'fill="currentColor" fill-rule="evenodd"').replace(/ fill="currentColor"\/>/, ' fill="currentColor" fill-rule="evenodd"/>')), { d: "M26.9 9.9H22.1L30.8 31.8H35.5L26.9 9.9Z M13 9.9L4.5 31.8H9.3L13 9.9Z", size: 40, rule: "evenodd" });
    assert.equal(logoFromSvg(LOGO.replace("</svg>", "<!-- <!-- x --> <circle r='1'/> --></svg>")), null, "a shape hidden behind a comment inside a comment");
    assert.equal(logoFromSvg(LOGO.replace("</svg>", "<!-- never closed</svg>")), null, "a comment that is never closed");
    assert.equal(logoFromSvg(PLACEHOLDER), null, "the catalogue's stand-in for a missing logo is not a logo");
    assert.equal(logoFromSvg(LOGO.replace('fill="currentColor"', 'fill="#ff0000"')), null, "other colours");
    assert.equal(logoFromSvg(LOGO.replace("<path ", '<path transform="scale(2)" ')), null, "transforms");
    assert.equal(logoFromSvg(LOGO.replace("</svg>", '<circle cx="1" cy="1" r="1" fill="currentColor"/></svg>')), null, "shapes other than paths");
    assert.equal(logoFromSvg(LOGO.replace('viewBox="0 0 40 40"', 'viewBox="0 0 40 20"')), null, "not square");
    assert.equal(logoFromSvg(LOGO.replace(/d="[^"]*"/, 'd="M0 0 L1 1 <script>"')), null, "path data with anything but path characters");
    assert.equal(logoFromSvg("<svg></svg>"), null);
  });

  it("serves a provider's logo from the catalogue's logo URL, and nothing else is tried when it is missing", async () => {
    const t = service({ "https://logos.test/openai.svg": LOGO, "https://logos.test/upstage.svg": PLACEHOLDER });
    const svc = new ConnectionService({ configDir: t.dir, fetcher: fakeNet({ "https://logos.test/openai.svg": LOGO, "https://logos.test/upstage.svg": PLACEHOLDER }).fetcher, logoUrl: "https://logos.test" });
    const a = svc.providerIcon("openai")!;
    assert.match(a, /^\/v1\/icons\/[a-f0-9]{24}$/);
    assert.deepEqual(await svc.icon(a.split("/").pop()!), { kind: "path", d: "M26.9 9.9H22.1L30.8 31.8H35.5L26.9 9.9Z M13 9.9L4.5 31.8H9.3L13 9.9Z", color: "currentColor", size: 40 });
    assert.equal(await svc.icon(svc.providerIcon("upstage")!.split("/").pop()!), null, "placeholder: the app shows a letter");
    assert.equal(svc.providerIcon("../x"), null, "ids are checked before they reach a URL");
    t.cleanup();
  });
});
