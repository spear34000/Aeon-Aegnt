import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";
import { crc32 } from "./crc32.ts";
import type { AeonConfig } from "../core/config.ts";
import {
  brandCandidates,
  brandKey,
  builtinConnections,
  iconLinks,
  logoFromSvg,
  mcpConfigConnection,
  registryConnection,
  relevance,
  siteDomain,
  skillConnection,
  type Connection,
  type IconPayload,
  type IconPlan,
} from "../core/connections.ts";
import type { Tool } from "../core/tools.ts";
import { safeFetch } from "./net-guard.ts";
import { SkillLibrary } from "./skill-library.ts";

export interface Fetched {
  status: number;
  text: string;
  bytes: Uint8Array;
  truncated: boolean;
}
export type Fetcher = (url: string, maxBytes: number) => Promise<Fetched>;

export interface Collection {
  id: string;
  title: Record<string, string>;
  queries: string[];
}

export interface ConnectionServiceOptions {
  configDir: string;
  /** Test seam. The default goes through the SSRF-guarded transport. */
  fetcher?: Fetcher;
  registryUrl?: string;
  brandIconsUrl?: string;
  logoUrl?: string;
  now?: () => number;
}

const REGISTRY = "https://registry.modelcontextprotocol.io";
/** Simple Icons (CC0 icon data; the marks remain trademarks of their owners). Pinned so slugs stay stable. */
const BRAND_ICONS = "https://cdn.jsdelivr.net/npm/simple-icons@16.37.0";
/** One SVG per provider id, next to the model list the provider catalogue already reads. */
const PROVIDER_LOGOS = "https://models.dev/logos";
const SEARCH_TTL_MS = 5 * 60_000;
const ICON_TTL_MS = 30 * 24 * 3600_000;
const ICON_MISS_TTL_MS = 24 * 3600_000;
const DEFAULT_COLLECTIONS = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "catalog", "collections.json");

const defaultFetcher: Fetcher = (url, maxBytes) => safeFetch(url, { maxBytes, timeoutMs: 15_000 });

const sig = (b: Uint8Array, ...bytes: number[]) => bytes.every((v, i) => b[i] === v);
const isPng = (b: Uint8Array) => sig(b, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);

function encodePng(w: number, h: number, rgba: Uint8Array): Uint8Array {
  const stride = w * 4 + 1;
  const raw = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) raw.set(rgba.subarray(y * w * 4, (y + 1) * w * 4), y * stride + 1);
  const chunk = (type: string, data: Buffer) => {
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const head = Buffer.alloc(4);
    head.writeUInt32BE(data.length);
    const tail = Buffer.alloc(4);
    tail.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([head, body, tail]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

/** Uncompressed bitmap as stored inside an .ico (XOR image followed by a 1-bit AND mask) -> PNG. */
function dibToPng(b: Uint8Array): Uint8Array | null {
  if (b.length < 40) return null;
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const header = v.getUint32(0, true);
  const w = v.getInt32(4, true);
  const h = Math.abs(v.getInt32(8, true)) >> 1;
  const bpp = v.getUint16(14, true);
  if (header < 40 || v.getUint32(16, true) !== 0 || w <= 0 || h <= 0 || w > 512 || h > 512 || ![8, 24, 32].includes(bpp)) return null;
  const colors = bpp === 8 ? v.getUint32(32, true) || 256 : 0;
  const pixels = header + colors * 4;
  const stride = (((w * bpp + 31) >> 5) << 2) >>> 0;
  const maskStride = (((w + 31) >> 5) << 2) >>> 0;
  const mask = pixels + stride * h;
  if (pixels + stride * h > b.length) return null;
  const hasMask = mask + maskStride * h <= b.length;
  const out = new Uint8Array(w * h * 4);
  let anyAlpha = false;
  for (let y = 0; y < h; y++) {
    const row = pixels + (h - 1 - y) * stride; // bottom-up
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 4;
      let p: number;
      let a = 255;
      if (bpp === 8) p = header + b[row + x]! * 4;
      else {
        p = row + x * (bpp >> 3);
        if (bpp === 32) a = b[p + 3]!;
      }
      out[o] = b[p + 2]!;
      out[o + 1] = b[p + 1]!;
      out[o + 2] = b[p]!;
      out[o + 3] = a;
      if (bpp === 32 && a !== 0) anyAlpha = true;
    }
  }
  // Old icons leave the alpha channel empty (or have none) and rely on the AND mask.
  if (bpp !== 32 || !anyAlpha) {
    for (let y = 0; y < h; y++) {
      const row = mask + (h - 1 - y) * maskStride;
      for (let x = 0; x < w; x++) {
        const transparent = hasMask && ((b[row + (x >> 3)]! >> (7 - (x & 7))) & 1) === 1;
        out[(y * w + x) * 4 + 3] = transparent ? 0 : 255;
      }
    }
  }
  return encodePng(w, h, out);
}

/** Returns bytes a UI can decode (PNG, JPEG, WebP, GIF), converting .ico when needed. SVG and unknown data are rejected. */
export function normaliseImage(b: Uint8Array): { mime: string; bytes: Uint8Array } | null {
  if (isPng(b)) return { mime: "image/png", bytes: b };
  if (sig(b, 0xff, 0xd8, 0xff)) return { mime: "image/jpeg", bytes: b };
  if (sig(b, 0x52, 0x49, 0x46, 0x46) && b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50) return { mime: "image/webp", bytes: b };
  if (sig(b, 0x47, 0x49, 0x46, 0x38)) return { mime: "image/gif", bytes: b };
  if (b.length >= 22 && sig(b, 0, 0, 1, 0)) {
    const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
    let best: { w: number; bpp: number; img: Uint8Array } | null = null;
    for (let i = 0; i < v.getUint16(4, true) && 22 + 16 * i <= b.length; i++) {
      const o = 6 + 16 * i;
      const w = b[o]! || 256;
      const bpp = v.getUint16(o + 6, true);
      const size = v.getUint32(o + 8, true);
      const off = v.getUint32(o + 12, true);
      if (off + size > b.length) continue;
      if (!best || w > best.w || (w === best.w && bpp > best.bpp)) best = { w, bpp, img: b.subarray(off, off + size) };
    }
    if (!best) return null;
    if (isPng(best.img)) return { mime: "image/png", bytes: best.img };
    const png = dibToPng(best.img);
    return png ? { mime: "image/png", bytes: png } : null;
  }
  return null;
}

interface CachedIcon {
  at: number;
  payload: IconPayload | null;
}

/**
 * Builds the list of connections from live sources and resolves their icons.
 * Outbound requests (registry search, icon lookups) are made by the PC, never by the paired device.
 */
export class ConnectionService {
  readonly skillsDir: string;
  /** The owner's skills, used by the routes when the running agent has no skill library of its own (it always has, outside tests). */
  readonly library: SkillLibrary;
  private readonly fetcher: Fetcher;
  private readonly registry: string;
  private readonly brands: string;
  private readonly now: () => number;
  private readonly iconDir: string;
  private readonly plans = new Map<string, IconPlan>();
  private readonly searches = new Map<string, { at: number; value: { connections: Connection[]; nextCursor: string | null } }>();
  private readonly pending = new Map<string, Promise<IconPayload | null>>();
  private brandIndex: Promise<Map<string, { slug: string; hex: string }>> | null = null;

  private readonly o: ConnectionServiceOptions;

  constructor(o: ConnectionServiceOptions) {
    this.o = o;
    this.skillsDir = join(o.configDir, "skills");
    this.library = new SkillLibrary({ userDir: this.skillsDir, lockFile: join(o.configDir, "skills-lock.json") });
    this.iconDir = join(o.configDir, "cache", "icons");
    this.fetcher = o.fetcher ?? defaultFetcher;
    this.registry = o.registryUrl ?? REGISTRY;
    this.brands = o.brandIconsUrl ?? BRAND_ICONS;
    this.now = o.now ?? Date.now;
  }

  /** API path of a provider's logo (models.dev serves one per provider id). The logo itself is fetched on first use. */
  providerIcon(id: string): string | null {
    if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(id)) return null;
    const key = createHash("sha256").update(`provider:${id}`).digest("hex").slice(0, 24);
    this.plans.set(key, { title: "", urls: [], host: null, githubOwner: null, svg: `${this.o.logoUrl ?? PROVIDER_LOGOS}/${id}.svg` });
    return `/v1/icons/${key}`;
  }

  private withIcon(c: Connection, plan: IconPlan): Connection {
    const key = createHash("sha256").update(c.id).digest("hex").slice(0, 24);
    this.plans.set(key, plan);
    return { ...c, icon: `/v1/icons/${key}` };
  }

  /** Everything usable or added right now: built-in tools, servers in config.json and skill folders. */
  installed(tools: Pick<Tool, "name" | "description">[], cfg: AeonConfig): Connection[] {
    const servers = (cfg.mcpServers ?? []).map((s) => {
      const { connection, plan } = mcpConfigConnection(s);
      return this.withIcon(connection, plan);
    });
    return [...builtinConnections(tools), ...servers, ...this.skills()];
  }

  private skills(): Connection[] {
    if (!existsSync(this.skillsDir)) return [];
    const out: Connection[] = [];
    for (const folder of readdirSync(this.skillsDir).sort()) {
      const file = join(this.skillsDir, folder, "SKILL.md");
      try {
        if (statSync(file).isFile() && statSync(file).size <= 200_000) out.push(skillConnection(folder, readFileSync(file, "utf8")));
      } catch {
        /* not a skill folder */
      }
    }
    return out;
  }

  collections(): Collection[] {
    const own = join(this.o.configDir, "collections.json");
    try {
      const raw: unknown = JSON.parse(readFileSync(existsSync(own) ? own : DEFAULT_COLLECTIONS, "utf8"));
      if (!Array.isArray(raw)) return [];
      return raw.flatMap((c): Collection[] => {
        const ok = c !== null && typeof c === "object" && typeof c.id === "string" && /^[a-z0-9-]{1,32}$/.test(c.id) && c.title !== null && typeof c.title === "object";
        if (!ok || !Array.isArray(c.queries)) return [];
        const queries = (c.queries as unknown[]).filter((q): q is string => typeof q === "string" && q.length > 0 && q.length <= 60).slice(0, 12);
        return [{ id: c.id, title: c.title as Record<string, string>, queries }];
      });
    } catch {
      return [];
    }
  }

  /** Live search of the MCP registry. Entries already in config.json come back as "added". */
  async search(q: string, cfg: AeonConfig, cursor?: string): Promise<{ connections: Connection[]; nextCursor: string | null }> {
    // The registry matches a substring of the server name, and names use hyphens where people type spaces.
    const text = q.trim().replace(/\s+/g, "-").slice(0, 60);
    const key = `${text}\n${cursor ?? ""}`;
    const hit = this.searches.get(key);
    let value = hit && this.now() - hit.at < SEARCH_TTL_MS ? hit.value : null;
    if (!value) {
      const url = new URL("/v0/servers", this.registry);
      url.searchParams.set("version", "latest");
      url.searchParams.set("limit", "40");
      if (text) url.searchParams.set("search", text);
      if (cursor) url.searchParams.set("cursor", cursor);
      const res = await this.fetcher(url.toString(), 2_000_000);
      if (res.status !== 200 || res.truncated) throw new Error(`registry answered ${res.status}`);
      const body = JSON.parse(res.text) as { servers?: unknown[]; metadata?: { nextCursor?: unknown } };
      const mapped = (Array.isArray(body.servers) ? body.servers : []).map(registryConnection).filter((x) => x !== null);
      const seen = new Set<string>();
      const connections = mapped
        .filter((m) => !seen.has(m.connection.id) && seen.add(m.connection.id))
        .map((m) => this.withIcon(m.connection, m.plan))
        .map((c, i) => ({ c, i, score: relevance(c, text) }))
        .sort((a, b) => b.score - a.score || a.i - b.i)
        .map((x) => x.c);
      value = { connections, nextCursor: typeof body.metadata?.nextCursor === "string" ? body.metadata.nextCursor : null };
      if (this.searches.size > 60) this.searches.clear();
      this.searches.set(key, { at: this.now(), value });
    }
    const added = new Set((cfg.mcpServers ?? []).map((s) => `mcp:${s.name}`));
    return { connections: value.connections.map((c) => (added.has(c.id) ? { ...c, state: "added" } : c)), nextCursor: value.nextCursor };
  }

  /** A collection is a list of search terms kept in a data file; its entries are whatever the registry returns for them. */
  async collection(id: string, cfg: AeonConfig): Promise<Connection[] | null> {
    const col = this.collections().find((c) => c.id === id);
    if (!col) return null;
    const results = await Promise.allSettled(col.queries.map((q) => this.search(q, cfg)));
    if (results.every((r) => r.status === "rejected")) throw new Error("registry unavailable");
    const seen = new Set<string>();
    const out: Connection[] = [];
    for (const r of results) {
      if (r.status !== "fulfilled") continue;
      for (const c of r.value.connections.slice(0, 5)) if (!seen.has(c.id) && seen.add(c.id)) out.push(c);
    }
    return out;
  }

  async icon(key: string): Promise<IconPayload | null> {
    const file = join(this.iconDir, `${key}.json`);
    try {
      const c = JSON.parse(readFileSync(file, "utf8")) as CachedIcon;
      if (this.now() - c.at < (c.payload ? ICON_TTL_MS : ICON_MISS_TTL_MS)) return c.payload;
    } catch {
      /* not cached */
    }
    const plan = this.plans.get(key);
    if (!plan) return null;
    let job = this.pending.get(key);
    if (!job) {
      job = this.resolve(plan)
        .catch(() => null)
        .then((payload) => {
          try {
            mkdirSync(this.iconDir, { recursive: true });
            writeFileSync(file, JSON.stringify({ at: this.now(), payload } satisfies CachedIcon));
          } catch {
            /* the cache is best effort */
          }
          return payload;
        })
        .finally(() => this.pending.delete(key));
      this.pending.set(key, job);
    }
    return job;
  }

  /** Publisher-declared icon, then a brand mark matching the title, then the publisher's site icon, then its GitHub avatar. */
  private async resolve(plan: IconPlan): Promise<IconPayload | null> {
    if (plan.svg) {
      try {
        const res = await this.fetcher(plan.svg, 64_000);
        const logo = res.status === 200 && !res.truncated ? logoFromSvg(res.text) : null;
        if (logo) return { kind: "path", d: logo.d, color: "currentColor", size: logo.size, ...(logo.rule ? { rule: logo.rule } : {}) };
      } catch {
        /* no logo: the app shows a letter */
      }
      if (!plan.title && plan.urls.length === 0) return null;
    }
    for (const u of plan.urls) {
      const img = await this.image(u);
      if (img) return img;
    }
    const brand = await this.brand(plan.title);
    if (brand) return brand;
    if (plan.host) {
      const site = await this.siteIcon(plan.host);
      if (site) return site;
    }
    return plan.githubOwner ? this.image(`https://github.com/${plan.githubOwner}.png?size=96`) : null;
  }

  private async image(url: string): Promise<IconPayload | null> {
    try {
      const res = await this.fetcher(url, 400_000);
      if (res.status !== 200 || res.truncated) return null;
      const img = normaliseImage(res.bytes);
      return img ? { kind: "image", mime: img.mime, data: Buffer.from(img.bytes).toString("base64") } : null;
    } catch {
      return null;
    }
  }

  private async siteIcon(hostname: string): Promise<IconPayload | null> {
    const domain = siteDomain(hostname);
    for (const base of [`https://${domain}/`, `https://www.${domain}/`]) {
      let links: string[];
      try {
        const page = await this.fetcher(base, 400_000);
        if (page.status !== 200) continue;
        links = iconLinks(page.text, base);
      } catch {
        continue;
      }
      for (const u of [...links.slice(0, 3), new URL("/favicon.ico", base).toString()]) {
        const img = await this.image(u);
        if (img) return img;
      }
      return null;
    }
    return null;
  }

  private async brand(title: string): Promise<IconPayload | null> {
    const candidates = brandCandidates(title);
    if (candidates.length === 0) return null;
    this.brandIndex ??= this.loadBrandIndex().catch(() => {
      this.brandIndex = null; // try again on a later request
      return new Map<string, { slug: string; hex: string }>();
    });
    const index = await this.brandIndex;
    for (const c of candidates) {
      const hit = index.get(c);
      if (!hit) continue;
      try {
        const res = await this.fetcher(`${this.brands}/icons/${hit.slug}.svg`, 64_000);
        const d = res.status === 200 && !res.truncated ? /<path d="([^"]+)"/.exec(res.text)?.[1] : undefined;
        if (d && /^[MmLlHhVvCcSsQqTtAaZz0-9eE .,+-]+$/.test(d)) return { kind: "path", d, color: `#${hit.hex}` };
      } catch {
        /* fall through to the next source */
      }
    }
    return null;
  }

  private async loadBrandIndex(): Promise<Map<string, { slug: string; hex: string }>> {
    const res = await this.fetcher(`${this.brands}/data/simple-icons.json`, 3_000_000);
    if (res.status !== 200 || res.truncated) throw new Error("brand index unavailable");
    const raw: unknown = JSON.parse(res.text);
    const map = new Map<string, { slug: string; hex: string }>();
    for (const i of Array.isArray(raw) ? raw : []) {
      if (i === null || typeof i !== "object" || typeof i.title !== "string" || typeof i.hex !== "string" || !/^[0-9A-Fa-f]{6}$/.test(i.hex)) continue;
      const slug = typeof i.slug === "string" ? i.slug : brandKey(i.title);
      if (/^[a-z0-9_]+$/.test(slug)) map.set(brandKey(i.title), { slug, hex: i.hex });
    }
    return map;
  }
}
