import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { dirname, extname, join, normalize, resolve, sep } from "node:path";
import { systemClock, type Clock } from "../../core/clock.ts";
import { parseConfig } from "../../core/config.ts";
import { builtinProviders, currentModel, selectModel, type CatalogProvider, type ModelPick } from "../../core/models/catalog.ts";
import { assertSecretName, type SecretStore } from "../../core/secrets.ts";
import type { AuditEvent, Task } from "../../core/types.ts";
import { parseCriterion } from "../../core/verify.ts";
import { loadConfig, saveConfig } from "../config-file.ts";
import type { ConnectionService } from "../connections.ts";
import { SkillError } from "../skills.ts";
import { FeedService, FEED_MIN_GAP_MS } from "../feed.ts";
import { articleImageFinder, ImageStore } from "../feed-images.ts";
import type { ProviderCatalog } from "../provider-catalog.ts";
import { isSealedBox, type ServerIdentity } from "./seal.ts";
import type { createRuntime } from "../runtime.ts";
import { canonicalRequest, DeviceRegistry, isAdmin, NonceCache, PairingManager, verifySignature, type PairRequest } from "./device-auth.ts";

type Runtime = ReturnType<typeof createRuntime>;

export interface AeonServerOptions {
  getRuntime: () => Runtime;
  /** Why the model layer is not usable yet (e.g. missing key), shown to the UI as a setup hint. */
  getProblem?: () => string | null;
  /** Called after settings change so the caller can rebuild the runtime (providers, consent). */
  onConfigChanged?: () => Promise<void> | void;
  secrets: SecretStore;
  /** Lists built-in tools, added MCP servers, skills and what a registry offers. Without it those routes answer 404. */
  connections?: ConnectionService;
  /** Provider and model catalogue. Without it only the built-in providers are listed. */
  providers?: ProviderCatalog;
  /** The interest feed. Defaults to one that keeps its result in the config folder and fetches Google News. */
  feed?: FeedService;
  /** Sealing key. With it, paired devices can send keys and receive pairing codes encrypted. */
  identity?: ServerIdentity;
  /** Addresses other devices can use to reach this server (shown when pairing a new device). */
  addresses?: () => string[];
  maxUploadBytes?: number;
  configDir: string;
  registry: DeviceRegistry;
  pairing: PairingManager;
  clock?: Clock;
  serverName?: string;
  /** Directory with the built web UI. Served without authentication (it is public code). */
  webRoot?: string;
  /** Test seam: decides whether a request comes from the machine running Aeon. Defaults to a loopback check. */
  isLocalRequest?: (req: IncomingMessage) => boolean;
  /** Scheduler pass interval; 0 disables the background loop (tests drive ticks manually). */
  tickIntervalMs?: number;
  maxBodyBytes?: number;
}

const FRESHNESS_MS = 60_000;
const MIME: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8", // Kotlin/Wasm and Skiko ship ES modules; browsers refuse them with another type
  ".map": "application/json; charset=utf-8",
  ".ico": "image/x-icon",
  ".txt": "text/plain; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".webmanifest": "application/manifest+json",
  ".wasm": "application/wasm",
};

class HttpError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const isLoopback = (addr: string | undefined): boolean => addr === "127.0.0.1" || addr === "::1" || addr === "::ffff:127.0.0.1";

function taskSummary(t: Task) {
  return {
    id: t.id,
    goal: t.goal,
    status: t.status,
    createdAt: t.createdAt,
    updatedAt: t.updatedAt,
    runAfter: t.runAfter,
    attempt: t.attempt,
    note: t.note ?? null,
    error: t.error ?? null,
    result: t.result ?? null,
    verification: t.verification ? { status: t.verification.status, checkedAt: t.verification.checkedAt } : null,
    pendingApprovalId: t.pendingApprovalId ?? null,
    stepCount: t.steps.length,
  };
}

export class AeonServer {
  private readonly o: AeonServerOptions;
  private readonly clock: Clock;
  private feedService: FeedService | null = null;
  private readonly nonces = new NonceCache(FRESHNESS_MS * 2);
  private server: Server | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private ticking = false;
  private readonly sseClients = new Set<ServerResponse>();

  constructor(o: AeonServerOptions) {
    this.o = o;
    this.clock = o.clock ?? systemClock;
    if (o.identity) o.pairing.serverKey = o.identity.publicKey;
  }

  /** False when the owner declined internet lookups for lists and logos (config.onlineLookups). */
  private online(): boolean {
    return loadConfig(this.o.configDir).onlineLookups !== false;
  }

  private async catalog(): Promise<CatalogProvider[]> {
    return this.o.providers ? this.o.providers.providers(this.online()) : builtinProviders();
  }

  /** Settings, keys, connections and devices can be changed by the machine itself or by a device the owner trusts with it. */
  private canAdmin(req: IncomingMessage, deviceId: string): boolean {
    return this.isLocal(req) || isAdmin(this.o.registry.get(deviceId));
  }

  async listen(port: number, host = "127.0.0.1"): Promise<{ port: number; host: string }> {
    this.server = createServer((req, res) => {
      this.handle(req, res).catch((e: unknown) => {
        const status = e instanceof HttpError ? e.status : 500;
        if (!(e instanceof HttpError) && process.env.AEON_DEBUG) console.error("internal error:", e); // for the owner's console, never sent to the client
        const msg = e instanceof HttpError ? e.message : "internal error"; // never leak internals
        if (!res.headersSent) this.json(res, status, { error: msg });
        else res.end();
      });
    });
    await new Promise<void>((r) => this.server!.listen(port, host, r));
    const interval = this.o.tickIntervalMs ?? 2000;
    if (interval > 0) this.timer = setInterval(() => void this.tick(), interval);
    const addr = this.server.address() as { port: number };
    return { port: addr.port, host };
  }

  async close(): Promise<void> {
    if (this.timer) clearInterval(this.timer);
    for (const c of this.sseClients) c.destroy();
    this.sseClients.clear();
    const s = this.server;
    if (!s) return;
    const closed = new Promise<void>((r) => s.close(() => r()));
    s.closeAllConnections();
    await closed;
  }

  /** One scheduler pass (also called by the background loop). */
  async tick(): Promise<void> {
    if (this.ticking) return;
    this.ticking = true;
    try {
      await this.o.getRuntime().engine.tick();
    } catch {
      /* a failing pass must not kill the loop; task failures are recorded on the tasks themselves */
    } finally {
      this.ticking = false;
    }
  }

  // ---------------------------------------------------------------- plumbing

  private isLocal(req: IncomingMessage): boolean {
    return this.o.isLocalRequest ? this.o.isLocalRequest(req) : isLoopback(req.socket.remoteAddress);
  }

  private json(res: ServerResponse, status: number, body: unknown): void {
    const data = JSON.stringify(body);
    res.writeHead(status, {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    });
    res.end(data);
  }

  private async readBody(req: IncomingMessage, limit = this.o.maxBodyBytes ?? 256 * 1024): Promise<Buffer> {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const c of req) {
      size += (c as Buffer).length;
      if (size > limit) throw new HttpError(413, "request body too large");
      chunks.push(c as Buffer);
    }
    return Buffer.concat(chunks);
  }

  private parseJson(body: Buffer): Record<string, unknown> {
    try {
      const v = JSON.parse(body.toString("utf8") || "{}");
      if (v === null || typeof v !== "object" || Array.isArray(v)) throw new Error();
      return v as Record<string, unknown>;
    } catch {
      throw new HttpError(400, "body must be a JSON object");
    }
  }

  /** Verifies the device signature. Throws HttpError(401) on any failure, with a deliberately vague message. */
  private authenticate(req: IncomingMessage, body: Buffer): { deviceId: string } {
    const h = (n: string) => (typeof req.headers[n] === "string" ? (req.headers[n] as string) : "");
    const deviceId = h("x-aeon-device");
    const ts = h("x-aeon-timestamp");
    const nonce = h("x-aeon-nonce");
    const sig = h("x-aeon-signature");
    const fail = () => new HttpError(401, "unauthorized");
    if (!deviceId || !ts || !nonce || !sig || nonce.length < 8 || nonce.length > 64) throw fail();
    const now = this.clock.now();
    const t = Number(ts);
    if (!Number.isFinite(t) || Math.abs(now - t) > FRESHNESS_MS) throw fail();
    const device = this.o.registry.get(deviceId);
    if (!device || device.revoked) throw fail();
    const data = canonicalRequest(req.method ?? "GET", req.url ?? "/", ts, nonce, body);
    if (!verifySignature(device.publicKey, data, sig)) throw fail();
    if (!this.nonces.use(`${deviceId}:${nonce}`, now)) throw fail(); // replay
    this.o.registry.touch(deviceId, now);
    return { deviceId };
  }

  // ---------------------------------------------------------------- routing

  private async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? "/", "http://localhost");
    const path = url.pathname;
    const method = req.method ?? "GET";

    // Dot segments are judged on the RAW request target: `new URL` would silently normalise them away.
    let rawPath = (req.url ?? "/").split("?")[0]!;
    try {
      rawPath = decodeURIComponent(rawPath);
    } catch {
      throw new HttpError(400, "bad path");
    }
    if (rawPath.replaceAll(String.fromCharCode(92), "/").split("/").includes("..")) throw new HttpError(400, "bad path");
    if (!path.startsWith("/v1/")) return this.serveStatic(path, method, res);

    // Unauthenticated: server hello and pairing.
    if (method === "GET" && path === "/v1/hello") {
      return this.json(res, 200, { name: this.o.serverName ?? "Aeon", api: 1, pairingRequired: true });
    }
    if (method === "POST" && path === "/v1/pair") {
      const body = this.parseJson(await this.readBody(req));
      const r = this.o.pairing.complete(body as unknown as PairRequest, this.clock.now());
      if (!r.ok) return this.json(res, r.reason === "locked" ? 429 : r.reason === "bad_request" ? 400 : 403, { error: r.reason === "invalid" ? "pairing failed" : r.reason });
      return this.json(res, 201, {
        deviceId: r.device.id,
        serverProof: r.serverProof,
        serverName: this.o.serverName ?? "Aeon",
        admin: r.device.admin !== false,
        ...(r.serverKey ? { serverKey: r.serverKey, serverKeyProof: r.serverKeyProof } : {}),
      });
    }

    // Uploads are large, so the cheap checks (known device, fresh timestamp) run before the body is read.
    const upload = method === "POST" && path === "/v1/files";
    if (upload) {
      const dev = this.o.registry.get(typeof req.headers["x-aeon-device"] === "string" ? req.headers["x-aeon-device"] : "");
      const ts = Number(req.headers["x-aeon-timestamp"]);
      if (!dev || dev.revoked || !Number.isFinite(ts) || Math.abs(this.clock.now() - ts) > FRESHNESS_MS) throw new HttpError(401, "unauthorized");
    }
    const raw = await this.readBody(req, upload ? (this.o.maxUploadBytes ?? 20 * 1024 * 1024) : undefined);
    const { deviceId } = this.authenticate(req, raw);
    const rt = this.o.getRuntime();
    const { store, engine } = rt;

    if (method === "GET" && path === "/v1/status") {
      const tasks = await store.listTasks();
      const counts: Record<string, number> = {};
      for (const t of tasks) counts[t.status] = (counts[t.status] ?? 0) + 1;
      const me = this.o.registry.get(deviceId);
      return this.json(res, 200, {
        name: this.o.serverName ?? "Aeon",
        device: deviceId,
        deviceName: me?.name ?? "",
        admin: this.canAdmin(req, deviceId),
        tasks: counts,
        secretsBackend: this.o.secrets.kind,
        providers: rt.router.health(),
        setupProblem: this.o.getProblem?.() ?? null,
        host: { platform: process.platform, arch: process.arch },
        addresses: this.o.addresses?.() ?? [],
        sealing: this.o.identity !== undefined,
      });
    }

    if (upload) {
      const name = (url.searchParams.get("name") ?? "")
        .normalize("NFC")
        .replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_")
        .replace(/^\.+/, "")
        .trim()
        .slice(0, 120);
      if (!name) throw new HttpError(400, "name is required");
      if (raw.length === 0) throw new HttpError(400, "the file is empty");
      const stamp = new Date(this.clock.now()).toISOString().replace(/[-:T.]/g, "").slice(0, 14);
      const rel = `uploads/${stamp}-${name}`;
      const target = rt.guard.resolve(rel);
      if (!target.ok) throw new HttpError(400, target.violation);
      mkdirSync(dirname(target.abs), { recursive: true });
      try {
        writeFileSync(target.abs, raw, { flag: "wx" });
      } catch {
        throw new HttpError(409, "a file with that name was uploaded in the same second");
      }
      return this.json(res, 201, { path: rel, size: raw.length });
    }

    if (path === "/v1/providers" && method === "GET") {
      const catalog = await this.catalog();
      const cfg = loadConfig(this.o.configDir);
      const names = new Set(await this.o.secrets.list());
      const used = new Set(cfg.providers);
      return this.json(res, 200, {
        providers: catalog.map((p) => ({ id: p.id, name: p.name, baseUrl: p.baseUrl, keyName: p.keyName, hasKey: p.keyName ? names.has(p.keyName) : true, local: p.local, doc: p.doc, efforts: p.efforts, modelCount: p.models.length, inUse: used.has(p.id), consented: p.local || cfg.dataConsent.includes(p.id), icon: this.online() ? (this.o.connections?.providerIcon(p.id) ?? null) : null })),
        current: currentModel(cfg, catalog),
        catalogError: this.online() ? (this.o.providers?.error ?? null) : null,
        online: this.online(),
      });
    }
    const modelsMatch = /^\/v1\/providers\/([a-z0-9][a-z0-9._-]{0,63})\/models$/.exec(path);
    if (modelsMatch && method === "GET") {
      const p = (await this.catalog()).find((x) => x.id === modelsMatch[1]);
      if (!p) throw new HttpError(404, "provider not found");
      return this.json(res, 200, { provider: p.id, models: p.models });
    }
    if (path === "/v1/model" && method === "POST") {
      if (!this.canAdmin(req, deviceId)) throw new HttpError(403, "this device is not allowed to change settings");
      const b = this.parseJson(raw);
      const catalog = await this.catalog();
      let next;
      try {
        if (typeof b.provider !== "string" || typeof b.model !== "string") throw new Error("provider and model are required");
        const custom = b.custom !== undefined && b.custom !== null && typeof b.custom === "object" ? (b.custom as Record<string, unknown>) : null;
        const pick: ModelPick = {
          provider: b.provider,
          model: b.model,
          ...(typeof b.reasoningEffort === "string" ? { reasoningEffort: b.reasoningEffort } : {}),
          ...(b.consent === true ? { consent: true } : {}),
          ...(custom && typeof custom.baseUrl === "string"
            ? { custom: { baseUrl: custom.baseUrl, ...(typeof custom.name === "string" ? { name: custom.name } : {}), ...(custom.needsKey === false ? { needsKey: false } : {}) } }
            : {}),
        };
        next = parseConfig(selectModel(loadConfig(this.o.configDir), pick, catalog));
      } catch (e) {
        throw new HttpError(400, (e as Error).message);
      }
      saveConfig(this.o.configDir, next);
      await this.o.onConfigChanged?.();
      return this.json(res, 200, { config: next, current: currentModel(next, catalog), setupProblem: this.o.getProblem?.() ?? null });
    }

    if (path === "/v1/tasks" && method === "GET") {
      return this.json(res, 200, { tasks: (await store.listTasks()).map(taskSummary).reverse() });
    }
    if (path === "/v1/tasks" && method === "POST") {
      const b = this.parseJson(raw);
      if (typeof b.goal !== "string" || b.goal.trim().length === 0 || b.goal.length > 4000) throw new HttpError(400, "goal must be a non-empty string (max 4000 chars)");
      const criteria = (Array.isArray(b.criteria) ? b.criteria : []).map((c) => parseCriterion(c, "user"));
      if (criteria.some((c) => c === null)) throw new HttpError(400, "invalid criteria");
      let runAfter: number | undefined;
      if (b.runAfter !== undefined) {
        if (typeof b.runAfter !== "number" || !Number.isFinite(b.runAfter)) throw new HttpError(400, "runAfter must be a unix-ms number");
        runAfter = b.runAfter;
      }
      const task = await engine.submit({ goal: b.goal.trim(), criteria: criteria as NonNullable<(typeof criteria)[number]>[], ...(runAfter !== undefined ? { runAfter } : {}) });
      void this.tick();
      return this.json(res, 201, { task: taskSummary(task) });
    }
    const taskMatch = /^\/v1\/tasks\/(task_[a-z0-9]+)(?:\/(cancel|verify|confirm))?$/.exec(path);
    if (taskMatch) {
      const id = taskMatch[1]!;
      const action = taskMatch[2];
      const t = await store.getTask(id);
      if (!t) throw new HttpError(404, "task not found");
      if (!action && method === "GET") return this.json(res, 200, { task: { ...taskSummary(t), plan: t.plan, criteria: t.criteria, steps: t.steps, verificationDetail: t.verification ?? null } });
      if (action && method === "POST") {
        if (action === "cancel") return this.json(res, 200, { task: taskSummary(await engine.cancel(id)) });
        if (action === "verify") return this.json(res, 200, { task: taskSummary(await engine.reverify(id)) });
        try {
          return this.json(res, 200, { task: taskSummary(await engine.confirmByUser(id)) });
        } catch (e) {
          throw new HttpError(409, (e as Error).message);
        }
      }
    }

    if (path === "/v1/approvals" && method === "GET") {
      const status = url.searchParams.get("status");
      const allowed = ["pending", "approved", "denied", "expired"];
      if (status && !allowed.includes(status)) throw new HttpError(400, "invalid status");
      await engine.sweepApprovals();
      return this.json(res, 200, { approvals: await store.listApprovals((status ?? undefined) as never) });
    }
    const apprMatch = /^\/v1\/approvals\/(appr_[a-z0-9]+)\/decide$/.exec(path);
    if (apprMatch && method === "POST") {
      const b = this.parseJson(raw);
      if (b.decision !== "approved" && b.decision !== "denied") throw new HttpError(400, 'decision must be "approved" or "denied"');
      try {
        const a = await engine.decideApproval(apprMatch[1]!, b.decision);
        void this.tick();
        return this.json(res, 200, { approval: a });
      } catch (e) {
        throw new HttpError(409, (e as Error).message);
      }
    }

    if (path === "/v1/usage" && method === "GET") return this.json(res, 200, { usage: await store.listUsage() });

    if (path === "/v1/config" && method === "GET") return this.json(res, 200, { config: loadConfig(this.o.configDir) });
    if (path === "/v1/config" && method === "PUT") {
      let cfg;
      try {
        cfg = parseConfig(this.parseJson(raw));
      } catch (e) {
        throw new HttpError(400, (e as Error).message);
      }
      // Changing where prompts and keys go (providers, consent, endpoints, MCP servers) is as sensitive as a secret.
      // It is accepted from the machine itself and from devices the owner lets change settings; a device limited
      // to chat and approvals cannot point a stored key at another host.
      const cur = loadConfig(this.o.configDir);
      const sensitive = ["providers", "dataConsent", "fallbackAllowed", "ollamaBaseUrl", "openaiCompatBaseUrl", "mcpServers", "endpoints", "onlineLookups"] as const;
      const changed = sensitive.filter((k) => JSON.stringify(cur[k] ?? null) !== JSON.stringify(cfg[k] ?? null));
      if (changed.length > 0 && !this.canAdmin(req, deviceId)) throw new HttpError(403, `this device is not allowed to change ${changed.join(", ")}`);
      saveConfig(this.o.configDir, cfg);
      await this.o.onConfigChanged?.();
      return this.json(res, 200, { config: cfg });
    }

    // Secrets are write-only over the API. The transport may be plain HTTP on a LAN, so a value from another device
    // must arrive sealed for this server's key (seal.ts); a plain value is accepted from the machine itself only.
    if (path === "/v1/secrets" && method === "GET") return this.json(res, 200, { backend: this.o.secrets.kind, names: await this.o.secrets.list() });
    const secMatch = /^\/v1\/secrets\/([A-Z][A-Z0-9_]{1,63})$/.exec(path);
    if (secMatch) {
      if (!this.canAdmin(req, deviceId)) throw new HttpError(403, "this device is not allowed to change keys");
      const name = secMatch[1]!;
      assertSecretName(name);
      if (method === "PUT") {
        const b = this.parseJson(raw);
        let value: unknown = b.value;
        if (b.sealed !== undefined) {
          if (!this.o.identity || !isSealedBox(b.sealed)) throw new HttpError(400, "sealed value not accepted");
          try {
            value = this.o.identity.open(b.sealed, `secret|${deviceId}|${name}`).toString("utf8");
          } catch {
            throw new HttpError(400, "sealed value could not be opened");
          }
        } else if (!this.isLocal(req)) {
          throw new HttpError(400, "send the value sealed: this connection is not encrypted");
        }
        if (typeof value !== "string" || value.length === 0 || value.length > 4096) throw new HttpError(400, "value must be a non-empty string");
        await this.o.secrets.set(name, value);
        await this.o.onConfigChanged?.();
        res.writeHead(204).end();
        return;
      }
      if (method === "DELETE") {
        await this.o.secrets.delete(name);
        await this.o.onConfigChanged?.();
        res.writeHead(204).end();
        return;
      }
    }

    if (path === "/v1/devices" && method === "GET") {
      return this.json(res, 200, {
        devices: this.o.registry.list().map(({ id, name, createdAt, lastSeenAt, revoked, admin }) => ({ id, name, createdAt, lastSeenAt, revoked, admin: admin !== false, current: id === deviceId })),
      });
    }
    const devMatch = /^\/v1\/devices\/(dev_[a-f0-9]+)$/.exec(path);
    if (devMatch && (method === "DELETE" || method === "PATCH")) {
      const id = devMatch[1]!;
      // A device may always remove itself (signing out); anything else needs the right to manage devices.
      if (!(method === "DELETE" && id === deviceId) && !this.canAdmin(req, deviceId)) throw new HttpError(403, "this device is not allowed to manage devices");
      if (method === "DELETE") {
        if (!this.o.registry.revoke(id)) throw new HttpError(404, "device not found");
        res.writeHead(204).end();
        return;
      }
      const b = this.parseJson(raw);
      const change: { name?: string; admin?: boolean } = {};
      if (b.name !== undefined) {
        if (typeof b.name !== "string" || !b.name.trim() || b.name.length > 64) throw new HttpError(400, "name must be 1-64 characters");
        change.name = b.name.trim();
      }
      if (b.admin !== undefined) {
        if (typeof b.admin !== "boolean") throw new HttpError(400, "admin must be true or false");
        if (id === deviceId && b.admin === false) throw new HttpError(409, "a device cannot remove its own right to change settings");
        change.admin = b.admin;
      }
      const d = this.o.registry.update(id, change);
      if (!d) throw new HttpError(404, "device not found");
      return this.json(res, 200, { device: { id: d.id, name: d.name, createdAt: d.createdAt, lastSeenAt: d.lastSeenAt, revoked: d.revoked, admin: d.admin !== false, current: d.id === deviceId } });
    }

    // A paired device can let another device in. The code is returned sealed for the asking device, because anyone
    // who reads a pairing code on the wire could pair a device of their own.
    if (path === "/v1/pairing-codes" && method === "POST") {
      if (!this.canAdmin(req, deviceId)) throw new HttpError(403, "this device is not allowed to pair devices");
      const b = this.parseJson(raw);
      const c = this.o.pairing.newCode(this.clock.now(), b.admin !== false);
      const body = { expiresAt: c.expiresAt, admin: c.admin, addresses: this.o.addresses?.() ?? [] };
      if (typeof b.epk === "string" && this.o.identity) {
        let sealed;
        try {
          sealed = this.o.identity.sealFor(b.epk, Buffer.from(c.code, "utf8"), `pairing-code|${deviceId}`);
        } catch {
          throw new HttpError(400, "epk is not a P-256 public key");
        }
        return this.json(res, 201, { ...body, sealed });
      }
      if (!this.isLocal(req)) throw new HttpError(400, "send a one-time key (epk): this connection is not encrypted");
      return this.json(res, 201, { ...body, code: c.code });
    }

    const conn = this.o.connections;
    // The feed of the owner's interests: cached headlines, refreshed on request (internet lookups must be on).
    if (path === "/v1/feed" || path === "/v1/feed/refresh" || path.startsWith("/v1/feed/image/")) {
      const feed = (this.feedService ??= this.o.feed ?? new FeedService({ file: join(this.o.configDir, "feed.json"), images: { store: new ImageStore(join(this.o.configDir, "feed-images")), finder: articleImageFinder() }, now: () => this.clock.now() }));
      const interests = loadConfig(this.o.configDir).profile?.interests ?? [];
      const imageMatch = /^\/v1\/feed\/image\/([a-f0-9]{12})$/.exec(path);
      if (imageMatch && method === "GET") {
        const img = feed.image(imageMatch[1]!);
        if (!img) throw new HttpError(404, "no image");
        res.writeHead(200, { "content-type": img.type, "content-length": img.bytes.length, "cache-control": "private, max-age=86400", "x-content-type-options": "nosniff" });
        res.end(img.bytes);
        return;
      }
      if (path === "/v1/feed" && method === "GET") return this.json(res, 200, { feed: feed.get(), interests, stale: feed.stale(interests), online: this.online() });
      if (path === "/v1/feed/refresh" && method === "POST") {
        if (!this.online()) throw new HttpError(403, "internet lookups are off in the settings");
        if (interests.length === 0) throw new HttpError(400, "pick interests first");
        const cached = feed.get();
        if (cached && cached.interests.join("\u0000") === interests.join("\u0000") && this.clock.now() - cached.updatedAt < FEED_MIN_GAP_MS) return this.json(res, 200, { feed: cached, interests, stale: false, online: true });
        // A model rewrites the headlines when one may be used; the headlines are public, and a failure keeps the originals.
        const chat = rt.router.candidates().length === 0 ? undefined : async (system: string, user: string) => (await rt.router.chat({ messages: [{ role: "system", content: system }, { role: "user", content: user }], maxTokens: 4000, temperature: 0.2 }, { taskId: null })).content;
        const data = await feed.refresh(interests, chat, { images: loadConfig(this.o.configDir).feedImages !== false });
        return this.json(res, 200, { feed: data, interests, stale: false, online: true });
      }
    }

    const lib = rt.skills ?? this.o.connections?.library;
    const skillMatch = /^\/v1\/skills(?:\/([a-z0-9][a-z0-9-]{0,63})(?:\/(enable|trust|update-preview|file|auto-run))?)?$/.exec(path);
    const sourceMatch = /^\/v1\/skill-sources\/(inspect|install|updates)$/.exec(path);
    if (lib && (skillMatch || sourceMatch)) {
      const scopeOf = (v: unknown, fallback: "user" | "project" = "user"): "user" | "project" => (v === "user" || v === "project" ? v : fallback);
      const admin = (what: string) => {
        if (!this.canAdmin(req, deviceId)) throw new HttpError(403, `this device is not allowed to ${what}`);
      };
      try {
        if (sourceMatch) {
          // The owner's acts: look at a source, install from it, check for newer versions. Nothing is installed by looking.
          admin("install skills");
          const what = sourceMatch[1];
          if (what === "updates" && method === "GET") {
            if (!this.online()) throw new HttpError(403, "internet lookups are off in the settings");
            return this.json(res, 200, { updates: await lib.check() });
          }
          if (method !== "POST") throw new HttpError(405, "use POST");
          const b = this.parseJson(raw);
          if (what === "inspect") {
            if (typeof b.source !== "string") throw new HttpError(400, "source is required");
            if (!/^(\/|\.|~|[A-Za-z]:[\\/])/.test(b.source.trim()) && !this.online()) throw new HttpError(403, "internet lookups are off in the settings");
            return this.json(res, 200, { preview: await lib.inspect(b.source) });
          }
          if (what === "install") {
            if (typeof b.previewId !== "string" || typeof b.name !== "string") throw new HttpError(400, "previewId and name are required");
            const skill = lib.install({ previewId: b.previewId, name: b.name, scope: scopeOf(b.scope), ...(typeof b.rename === "string" ? { rename: b.rename } : {}), ...(b.replace === true ? { replace: true } : {}), ...(b.autoRun === false ? { autoRun: false } : {}) });
            return this.json(res, 201, { skill });
          }
        }
        if (skillMatch) {
          const folder = skillMatch[1];
          const action = skillMatch[2];
          const scope = scopeOf(url.searchParams.get("scope"));
          if (method === "GET" && !folder) return this.json(res, 200, { skills: lib.list(), dir: lib.userDir, projectDir: lib.project?.dir ?? null, canEdit: this.canAdmin(req, deviceId) });
          if (method === "GET" && folder && action === "file") {
            // Reading a file of a skill (any state) so the owner can read it before approving. Open to any paired device like the rest of the reads.
            const rel = url.searchParams.get("path") ?? "";
            const f = lib.readSupportFile(scope, folder, rel);
            if (!f) throw new HttpError(404, "file not found");
            return this.json(res, 200, { path: rel, ...f });
          }
          if (method === "GET" && folder && !action) {
            // With a scope: that one, whatever its state (an unapproved skill has to be readable). Without: the one in use, else the first.
            const same = lib.list().filter((x) => x.folder === folder);
            const hit = url.searchParams.has("scope") ? same.find((x) => x.scope === scope) : (same.find((x) => x.active) ?? same[0]);
            const full = hit ? (hit.scope === "project" ? lib.project : lib.user)?.read(folder) : null;
            if (!hit || !full) throw new HttpError(404, "skill not found");
            return this.json(res, 200, { skill: { ...full, scope: hit.scope, status: hit.status, enabled: hit.enabled, active: hit.active, inactiveBecause: hit.inactiveBecause, ...(hit.source ? { source: hit.source } : {}), hash: hit.hash } });
          }
          if (folder && method === "PUT" && !action) {
            admin("change skills");
            // Skills are made by the agent (the owner approves each one). The owner can correct or delete what exists, not create one.
            const b = this.parseJson(raw);
            if (typeof b.description !== "string" || typeof b.body !== "string") throw new HttpError(400, "description and body are required");
            const strings = (v: unknown): Record<string, string> | undefined => (v !== null && typeof v === "object" && !Array.isArray(v) && Object.values(v).every((x) => typeof x === "string") ? (v as Record<string, string>) : undefined);
            const saved = lib.writeAsOwner({
              name: folder, description: b.description, body: b.body,
              ...(typeof b.license === "string" ? { license: b.license } : {}),
              ...(typeof b.compatibility === "string" ? { compatibility: b.compatibility } : {}),
              ...(strings(b.metadata) ? { metadata: strings(b.metadata)! } : {}),
            });
            return this.json(res, 200, { skill: saved, trusted: saved.trusted });
          }
          if (folder && method === "DELETE" && !action) {
            admin("change skills");
            if (!lib.remove(scope, folder)) throw new HttpError(404, "skill not found");
            res.writeHead(204).end();
            return;
          }
          if (folder && method === "POST" && action) {
            admin("change skills");
            const b = this.parseJson(raw);
            const sc = scopeOf(b.scope);
            if (action === "auto-run") return this.json(res, 200, { skill: lib.setAutoRun(sc, folder, b.enabled !== false) });
            if (action === "enable") return this.json(res, 200, { skill: lib.setEnabled(sc, folder, b.enabled !== false) });
            if (action === "trust") {
              if (typeof b.hash !== "string") throw new HttpError(400, "hash is required: the hash of the skill you were shown");
              return this.json(res, 200, { skill: lib.trust(sc, folder, b.hash) });
            }
            if (!this.online() && lib.get(sc, folder)?.source?.type === "github") throw new HttpError(403, "internet lookups are off in the settings");
            return this.json(res, 200, await lib.previewUpdate(sc, folder));
          }
        }
      } catch (e) {
        if (e instanceof SkillError) throw new HttpError(e.status, e.message);
        throw e;
      }
    }
    if (conn && method === "GET") {
      if (path === "/v1/connections") {
        return this.json(res, 200, { connections: conn.installed(rt.tools.list(), loadConfig(this.o.configDir)), skillsDir: conn.skillsDir, canEdit: this.canAdmin(req, deviceId) });
      }
      if (path === "/v1/catalog/collections") return this.json(res, 200, { collections: conn.collections() });
      if (path === "/v1/catalog") {
        const q = url.searchParams.get("q") ?? "";
        const collection = url.searchParams.get("collection");
        const cursor = url.searchParams.get("cursor") ?? undefined;
        if (q.length > 60 || (cursor !== undefined && cursor.length > 300)) throw new HttpError(400, "query too long");
        const cfg = loadConfig(this.o.configDir);
        if (cfg.onlineLookups === false) return this.json(res, 200, { connections: [], nextCursor: null, disabled: true });
        try {
          if (collection) {
            const list = await conn.collection(collection, cfg);
            if (!list) throw new HttpError(404, "collection not found");
            return this.json(res, 200, { connections: list, nextCursor: null });
          }
          return this.json(res, 200, await conn.search(q, cfg, cursor));
        } catch (e) {
          if (e instanceof HttpError) throw e;
          throw new HttpError(502, "catalog unavailable");
        }
      }
      const iconMatch = /^\/v1\/icons\/([a-f0-9]{24})$/.exec(path);
      if (iconMatch) {
        if (!this.online()) throw new HttpError(404, "no icon");
        const icon = await conn.icon(iconMatch[1]!);
        if (!icon) throw new HttpError(404, "no icon");
        return this.json(res, 200, { icon });
      }
    }

    if (path === "/v1/events" && method === "GET") return this.sse(req, res, url, store);

    throw new HttpError(404, "not found");
  }

  private sse(req: IncomingMessage, res: ServerResponse, url: URL, store: Runtime["store"]): void {
    res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-store", connection: "keep-alive", "x-content-type-options": "nosniff" });
    this.sseClients.add(res);
    let last = Number(req.headers["last-event-id"] ?? url.searchParams.get("after") ?? 0) || 0;
    let busy = false;
    const push = async () => {
      if (busy) return;
      busy = true;
      try {
        const events: AuditEvent[] = (await store.listEvents()).filter((e) => (e.id ?? 0) > last);
        for (const e of events) {
          res.write(`id: ${e.id}\nevent: audit\ndata: ${JSON.stringify(e)}\n\n`);
          last = e.id ?? last;
        }
        if (events.length === 0) res.write(": keepalive\n\n");
      } finally {
        busy = false;
      }
    };
    void push();
    const timer = setInterval(() => void push(), 1000);
    const stop = () => {
      clearInterval(timer);
      this.sseClients.delete(res);
    };
    req.on("close", stop);
    res.on("close", stop);
  }

  private serveStatic(path: string, method: string, res: ServerResponse): void {
    const root = this.o.webRoot ? resolve(this.o.webRoot) : null;
    const headers = {
      "x-content-type-options": "nosniff",
      "x-frame-options": "DENY",
      "referrer-policy": "no-referrer",
      // 'wasm-unsafe-eval' lets the browser compile WebAssembly (the Kotlin web UI) without allowing eval() of strings.
      "content-security-policy": "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; worker-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'",
    };
    if (!root || (method !== "GET" && method !== "HEAD")) {
      res.writeHead(404, headers).end("not found");
      return;
    }
    let rel: string;
    try {
      rel = normalize(decodeURIComponent(path)).replace(/^([/\\])+/, "");
    } catch {
      res.writeHead(400, headers).end("bad request");
      return;
    }
    let file = join(root, rel || "index.html");
    if (file !== root && !file.startsWith(root + sep)) {
      res.writeHead(403, headers).end("forbidden");
      return;
    }
    if (!existsSync(file) || !statSync(file).isFile()) file = join(root, "index.html"); // SPA fallback
    if (!existsSync(file)) {
      res.writeHead(404, headers).end("not found");
      return;
    }
    res.writeHead(200, { ...headers, "content-type": MIME[extname(file)] ?? "application/octet-stream", "cache-control": "no-cache" });
    res.end(method === "HEAD" ? undefined : readFileSync(file));
  }
}
