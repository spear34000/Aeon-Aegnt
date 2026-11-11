import { existsSync, readFileSync, statSync } from "node:fs";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { extname, join, normalize, resolve, sep } from "node:path";
import { systemClock, type Clock } from "../../core/clock.ts";
import { parseConfig } from "../../core/config.ts";
import { assertSecretName, type SecretStore } from "../../core/secrets.ts";
import type { AuditEvent, Task } from "../../core/types.ts";
import { parseCriterion } from "../../core/verify.ts";
import { loadConfig, saveConfig } from "../config-file.ts";
import type { ConnectionService } from "../connections.ts";
import type { createRuntime } from "../runtime.ts";
import { canonicalRequest, DeviceRegistry, NonceCache, PairingManager, verifySignature, type PairRequest } from "./device-auth.ts";

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
  private readonly nonces = new NonceCache(FRESHNESS_MS * 2);
  private server: Server | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private ticking = false;
  private readonly sseClients = new Set<ServerResponse>();

  constructor(o: AeonServerOptions) {
    this.o = o;
    this.clock = o.clock ?? systemClock;
  }

  async listen(port: number, host = "127.0.0.1"): Promise<{ port: number; host: string }> {
    this.server = createServer((req, res) => {
      this.handle(req, res).catch((e: unknown) => {
        const status = e instanceof HttpError ? e.status : 500;
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

  private async readBody(req: IncomingMessage): Promise<Buffer> {
    const limit = this.o.maxBodyBytes ?? 256 * 1024;
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
      return this.json(res, 201, { deviceId: r.device.id, serverProof: r.serverProof, serverName: this.o.serverName ?? "Aeon" });
    }

    const raw = await this.readBody(req);
    const { deviceId } = this.authenticate(req, raw);
    const rt = this.o.getRuntime();
    const { store, engine } = rt;

    if (method === "GET" && path === "/v1/status") {
      const tasks = await store.listTasks();
      const counts: Record<string, number> = {};
      for (const t of tasks) counts[t.status] = (counts[t.status] ?? 0) + 1;
      return this.json(res, 200, { name: this.o.serverName ?? "Aeon", device: deviceId, tasks: counts, secretsBackend: this.o.secrets.kind, providers: rt.router.health(), setupProblem: this.o.getProblem?.() ?? null });
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
      // Changing where prompts and keys go (providers, consent, endpoints) is as sensitive as a secret: a lost or
      // compromised phone must not be able to point the stored key at another host. Local machine only.
      const cur = loadConfig(this.o.configDir);
      // An added MCP server is another host the agent may talk to, so it follows the same rule.
      const sensitive = ["providers", "dataConsent", "fallbackAllowed", "ollamaBaseUrl", "openaiCompatBaseUrl", "mcpServers"] as const;
      const changed = sensitive.filter((k) => JSON.stringify(cur[k] ?? null) !== JSON.stringify(cfg[k] ?? null));
      if (changed.length > 0 && !this.isLocal(req)) throw new HttpError(403, `changing ${changed.join(", ")} is only allowed from the machine running Aeon`);
      saveConfig(this.o.configDir, cfg);
      await this.o.onConfigChanged?.();
      return this.json(res, 200, { config: cfg });
    }

    // Secrets are write-only over the API, and only from the PC itself: the transport is plain HTTP on a LAN.
    if (path === "/v1/secrets" && method === "GET") return this.json(res, 200, { backend: this.o.secrets.kind, names: await this.o.secrets.list() });
    const secMatch = /^\/v1\/secrets\/([A-Z][A-Z0-9_]{1,63})$/.exec(path);
    if (secMatch) {
      if (!this.isLocal(req)) throw new HttpError(403, "secrets can only be changed from the machine running Aeon");
      const name = secMatch[1]!;
      assertSecretName(name);
      if (method === "PUT") {
        const b = this.parseJson(raw);
        if (typeof b.value !== "string" || b.value.length === 0 || b.value.length > 4096) throw new HttpError(400, "value must be a non-empty string");
        await this.o.secrets.set(name, b.value);
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
      return this.json(res, 200, { devices: this.o.registry.list().map(({ id, name, createdAt, lastSeenAt, revoked }) => ({ id, name, createdAt, lastSeenAt, revoked })) });
    }
    const devMatch = /^\/v1\/devices\/(dev_[a-f0-9]+)$/.exec(path);
    if (devMatch && method === "DELETE") {
      if (!this.o.registry.revoke(devMatch[1]!)) throw new HttpError(404, "device not found");
      res.writeHead(204).end();
      return;
    }

    const conn = this.o.connections;
    if (conn && method === "GET") {
      if (path === "/v1/connections") {
        return this.json(res, 200, { connections: conn.installed(rt.tools.list(), loadConfig(this.o.configDir)), skillsDir: conn.skillsDir, canEdit: this.isLocal(req) });
      }
      if (path === "/v1/catalog/collections") return this.json(res, 200, { collections: conn.collections() });
      if (path === "/v1/catalog") {
        const q = url.searchParams.get("q") ?? "";
        const collection = url.searchParams.get("collection");
        const cursor = url.searchParams.get("cursor") ?? undefined;
        if (q.length > 60 || (cursor !== undefined && cursor.length > 300)) throw new HttpError(400, "query too long");
        const cfg = loadConfig(this.o.configDir);
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
