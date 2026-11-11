import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { builtinProviders, catalogFromModelsDev, type CatalogProvider } from "../core/models/catalog.ts";
import { safeFetch } from "./net-guard.ts";
import type { Fetcher } from "./connections.ts";

const SOURCE = "https://models.dev/api.json";
const TTL_MS = 24 * 3600_000;

interface Cached {
  at: number;
  providers: CatalogProvider[];
}

/**
 * Providers and models, read from models.dev by the machine that runs Aeon and cached for a day.
 * When the catalogue cannot be fetched, the last copy is used; without one, only the built-in providers are listed.
 */
export class ProviderCatalog {
  private readonly file: string;
  private readonly fetcher: Fetcher;
  private readonly url: string;
  private readonly now: () => number;
  private memory: Cached | null = null;
  private loading: Promise<Cached | null> | null = null;
  private lastTry = -Infinity;
  /** Why the live catalogue is not available, for the UI. */
  error: string | null = null;

  constructor(o: { configDir: string; fetcher?: Fetcher; url?: string; now?: () => number }) {
    this.file = join(o.configDir, "cache", "providers.json");
    this.fetcher = o.fetcher ?? ((url, maxBytes) => safeFetch(url, { maxBytes, timeoutMs: 30_000 }));
    this.url = o.url ?? SOURCE;
    this.now = o.now ?? Date.now;
  }

  private readDisk(): Cached | null {
    try {
      const c = JSON.parse(readFileSync(this.file, "utf8")) as Cached;
      return typeof c.at === "number" && Array.isArray(c.providers) ? c : null;
    } catch {
      return null;
    }
  }

  private async refresh(stale: Cached | null): Promise<Cached | null> {
    try {
      const res = await this.fetcher(this.url, 32_000_000);
      if (res.status !== 200 || res.truncated) throw new Error(`models.dev answered ${res.status}`);
      const providers = catalogFromModelsDev(JSON.parse(res.text));
      if (providers.length === 0) throw new Error("models.dev returned no usable providers");
      const fresh: Cached = { at: this.now(), providers };
      try {
        mkdirSync(join(this.file, ".."), { recursive: true });
        writeFileSync(this.file, JSON.stringify(fresh));
      } catch {
        /* the cache is best effort */
      }
      this.error = null;
      return fresh;
    } catch (e) {
      this.error = (e as Error).message;
      return stale;
    }
  }

  /** Built-in providers first, then the catalogue in name order. With [online] false nothing is fetched: only the last copy is used. */
  async providers(online = true): Promise<CatalogProvider[]> {
    let c = this.memory ?? this.readDisk();
    // After a failed fetch, wait ten minutes before asking again so a dead network does not slow every request.
    if (online && (!c || this.now() - c.at >= TTL_MS) && (this.loading || this.now() - this.lastTry >= 600_000)) {
      this.lastTry = this.now();
      this.loading ??= this.refresh(c).finally(() => (this.loading = null));
      c = await this.loading;
    }
    this.memory = c;
    return [...builtinProviders(), ...(c?.providers ?? [])];
  }
}
