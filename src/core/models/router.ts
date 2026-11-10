import type { Clock } from "../clock.ts";
import { ModelError } from "../errors.ts";
import type { Store } from "../store.ts";
import type { ChatRequest, ChatResult, ModelProvider } from "./types.ts";

export interface ProviderLimits {
  /** Local soft limits (we stop calling before the provider blocks us). */
  maxCallsPerDay?: number;
  maxTokensPerDay?: number;
}

export interface RouterConfig {
  /** Priority order. The first entry is the primary. */
  providers: ModelProvider[];
  /**
   * Owner-approved automatic switching to later providers (master directive section 9).
   * When false, only the primary is ever called.
   */
  fallbackAllowed: boolean;
  /** Provider ids the owner consented to send prompts to. External providers need an entry. */
  dataConsent: ReadonlySet<string>;
  limits?: Record<string, ProviderLimits>;
  store: Store;
  clock: Clock;
}

/** Every usable provider is waiting on a quota/rate limit; the task should wait, not fail. */
export class QuotaWaitError extends Error {
  readonly until: number;
  readonly providers: string[];
  constructor(until: number, providers: string[], detail: string) {
    super(`All permitted model providers are rate/quota limited until ${new Date(until).toISOString()}: ${detail}`);
    this.name = "QuotaWaitError";
    this.until = until;
    this.providers = providers;
  }
}

const FAILOVER_KINDS = new Set(["rate_limited", "quota_exhausted", "server", "network", "timeout", "unavailable", "auth", "invalid_response"]);
const DEFAULT_RATE_BACKOFF_MS = 60_000;
const DEFAULT_QUOTA_BACKOFF_MS = 60 * 60_000;
const DAY_MS = 86_400_000;

class Semaphore {
  private n: number;
  private waiters: (() => void)[] = [];
  constructor(n: number) {
    this.n = n;
  }
  async acquire(): Promise<() => void> {
    if (this.n > 0) this.n -= 1;
    else await new Promise<void>((r) => this.waiters.push(r));
    let released = false;
    return () => {
      if (released) return;
      released = true;
      const w = this.waiters.shift();
      if (w) w();
      else this.n += 1;
    };
  }
}

export class ModelRouter {
  private readonly cfg: RouterConfig;
  private blockedUntil = new Map<string, number>();
  private consecutiveFailures = new Map<string, number>();
  private semaphores = new Map<string, Semaphore>();

  constructor(cfg: RouterConfig) {
    this.cfg = cfg;
    for (const p of cfg.providers) {
      this.semaphores.set(p.id, new Semaphore(Math.max(1, p.capabilities().maxConcurrency)));
    }
  }

  /** Providers that this owner has consented to use, honoring the fallback switch. */
  candidates(): ModelProvider[] {
    const permitted = this.cfg.providers.filter((p) => !p.external || this.cfg.dataConsent.has(p.id));
    if (this.cfg.fallbackAllowed) return permitted;
    const primary = this.cfg.providers[0];
    return primary && permitted.includes(primary) ? [primary] : [];
  }

  async chat(req: ChatRequest, ctx: { taskId: string | null }): Promise<ChatResult> {
    const now = () => this.cfg.clock.now();
    const candidates = this.candidates();
    if (candidates.length === 0) {
      const needConsent = this.cfg.providers.filter((p) => p.external && !this.cfg.dataConsent.has(p.id)).map((p) => p.id);
      throw new ModelError(
        "consent_required",
        "router",
        needConsent.length > 0
          ? `no model provider is permitted: ${needConsent.join(", ")} would send your prompts to an external service. Re-run with --consent ${needConsent.join(",")} to allow it.`
          : "no model provider is configured",
      );
    }
    const waits: { id: string; until: number }[] = [];
    let lastError: ModelError | undefined;

    for (const p of candidates) {
      const blocked = this.blockedUntil.get(p.id) ?? 0;
      if (blocked > now()) {
        waits.push({ id: p.id, until: blocked });
        continue;
      }
      const limitHit = await this.localLimitReachedUntil(p.id);
      if (limitHit) {
        this.blockedUntil.set(p.id, limitHit);
        waits.push({ id: p.id, until: limitHit });
        continue;
      }

      const release = await this.semaphores.get(p.id)!.acquire();
      try {
        const result = await p.chat(req);
        this.consecutiveFailures.set(p.id, 0);
        await this.cfg.store.addUsage({
          at: now(),
          provider: p.id,
          model: result.model,
          taskId: ctx.taskId,
          ...result.usage,
          ok: true,
        });
        return result;
      } catch (e) {
        if (!(e instanceof ModelError)) throw e;
        lastError = e;
        await this.cfg.store.addUsage({
          at: now(),
          provider: p.id,
          model: p.capabilities().modelId,
          taskId: ctx.taskId,
          inputTokens: 0,
          outputTokens: 0,
          reasoningTokens: 0,
          ok: false,
        });
        const until = this.noteFailure(p.id, e);
        if (e.kind === "rate_limited" || e.kind === "quota_exhausted") waits.push({ id: p.id, until });
        if (!FAILOVER_KINDS.has(e.kind)) throw e; // e.g. bad_request: do not shop the prompt around
      } finally {
        release();
      }
    }

    if (waits.length > 0 && waits.length === candidates.length) {
      const until = Math.min(...waits.map((w) => w.until));
      throw new QuotaWaitError(until, waits.map((w) => w.id), lastError?.message ?? "limits reached");
    }
    throw lastError ?? new ModelError("unavailable", "router", "no provider could serve the request");
  }

  /** Returns the time until which the provider should be considered blocked. */
  private noteFailure(id: string, e: ModelError): number {
    const t = this.cfg.clock.now();
    let until = t;
    if (e.kind === "rate_limited") until = t + (e.retryAfterMs ?? DEFAULT_RATE_BACKOFF_MS);
    else if (e.kind === "quota_exhausted") until = t + (e.retryAfterMs ?? DEFAULT_QUOTA_BACKOFF_MS);
    else {
      const n = (this.consecutiveFailures.get(id) ?? 0) + 1;
      this.consecutiveFailures.set(id, n);
      if (n >= 3 || e.kind === "auth") until = t + 30_000 * Math.min(n, 10);
    }
    if (until > t) this.blockedUntil.set(id, until);
    return until;
  }

  private async localLimitReachedUntil(providerId: string): Promise<number | null> {
    const limits = this.cfg.limits?.[providerId];
    if (!limits || (limits.maxCallsPerDay === undefined && limits.maxTokensPerDay === undefined)) return null;
    const t = this.cfg.clock.now();
    const dayStart = Math.floor(t / DAY_MS) * DAY_MS;
    const used = (await this.cfg.store.listUsage(dayStart)).filter((u) => u.provider === providerId && u.ok);
    const tokens = used.reduce((s, u) => s + u.inputTokens + u.outputTokens + u.reasoningTokens, 0);
    const overCalls = limits.maxCallsPerDay !== undefined && used.length >= limits.maxCallsPerDay;
    const overTokens = limits.maxTokensPerDay !== undefined && tokens >= limits.maxTokensPerDay;
    return overCalls || overTokens ? dayStart + DAY_MS : null;
  }

  health(): { provider: string; blockedUntil: number | null }[] {
    const t = this.cfg.clock.now();
    return this.cfg.providers.map((p) => {
      const b = this.blockedUntil.get(p.id) ?? 0;
      return { provider: p.id, blockedUntil: b > t ? b : null };
    });
  }
}
