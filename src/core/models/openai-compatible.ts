import { ModelError } from "../errors.ts";
import { anySignal } from "./abort.ts";
import type { Redactor } from "../redact.ts";
import type { ChatRequest, ChatResult, ModelCapabilities, ModelProvider } from "./types.ts";

export interface OpenAICompatibleConfig {
  id: string;
  baseUrl: string; // e.g. https://api.exaone.ai/v1
  model: string;
  apiKey?: string;
  external: boolean;
  unreleased?: boolean;
  capabilities: Omit<ModelCapabilities, "modelId">;
  /** Extra JSON body fields (e.g. reasoning_effort for EXAONE). */
  extraBody?: Record<string, unknown>;
  /**
   * Fields the service may not know (e.g. `reasoning_effort` on a model without reasoning). If the service
   * rejects the request as malformed, it is sent once more without them and they stay off for this provider.
   */
  optionalBody?: Record<string, unknown>;
  timeoutMs?: number;
  fetch?: typeof fetch;
  redactor?: Redactor;
}

/** Generic client for /chat/completions. Used directly and as the base for presets. */
export class OpenAICompatibleProvider implements ModelProvider {
  readonly id: string;
  readonly external: boolean;
  readonly unreleased: boolean;
  private readonly cfg: OpenAICompatibleConfig;
  private readonly doFetch: typeof fetch;
  private optionalOff = false;

  constructor(cfg: OpenAICompatibleConfig) {
    this.cfg = cfg;
    this.id = cfg.id;
    this.external = cfg.external;
    this.unreleased = cfg.unreleased ?? false;
    this.doFetch = cfg.fetch ?? fetch;
    cfg.redactor?.register(cfg.apiKey);
  }

  capabilities(): ModelCapabilities {
    return { modelId: this.cfg.model, ...this.cfg.capabilities };
  }

  private scrub(text: string): string {
    return this.cfg.redactor ? this.cfg.redactor.redact(text) : text;
  }

  async chat(req: ChatRequest): Promise<ChatResult> {
    const url = `${this.cfg.baseUrl.replace(/\/+$/, "")}/chat/completions`;
    const headers: Record<string, string> = { "content-type": "application/json" };
    if (this.cfg.apiKey) headers["authorization"] = `Bearer ${this.cfg.apiKey}`;
    const base: Record<string, unknown> = {
      model: this.cfg.model,
      messages: req.messages,
      stream: false,
      ...this.cfg.extraBody,
    };
    if (req.maxTokens !== undefined) base.max_tokens = req.maxTokens;
    if (req.temperature !== undefined) base.temperature = req.temperature;

    const timeout = AbortSignal.timeout(this.cfg.timeoutMs ?? 120_000);
    const signal = req.signal ? anySignal([req.signal, timeout]) : timeout;

    const send = async (body: Record<string, unknown>): Promise<Response> => {
      try {
        return await this.doFetch(url, { method: "POST", headers, body: JSON.stringify(body), signal });
      } catch (e) {
        const err = e as Error;
        if (err.name === "TimeoutError" || err.name === "AbortError") {
          throw new ModelError("timeout", this.id, `request timed out or was aborted`);
        }
        throw new ModelError("network", this.id, this.scrub(`network error: ${err.message}`));
      }
    };

    const optional = !this.optionalOff && this.cfg.optionalBody && Object.keys(this.cfg.optionalBody).length > 0 ? this.cfg.optionalBody : null;
    let res = await send(optional ? { ...base, ...optional } : base);
    if (optional && (res.status === 400 || res.status === 422)) {
      this.optionalOff = true;
      res = await send(base);
    }

    if (!res.ok) throw await this.toModelError(res);

    let json: unknown;
    try {
      json = await res.json();
    } catch {
      throw new ModelError("invalid_response", this.id, "response was not valid JSON", { status: res.status });
    }
    return this.parse(json);
  }

  private parse(json: unknown): ChatResult {
    const o = json as {
      choices?: { finish_reason?: unknown; message?: { content?: unknown; reasoning_content?: unknown } }[];
      usage?: {
        prompt_tokens?: number;
        completion_tokens?: number;
        reasoning_tokens?: number;
        completion_tokens_details?: { reasoning_tokens?: number };
      };
    };
    const msg = o?.choices?.[0]?.message;
    if (!msg || typeof msg.content !== "string") {
      throw new ModelError("invalid_response", this.id, "response had no assistant message content");
    }
    const finish = o?.choices?.[0]?.finish_reason;
    if (finish === "length" || msg.content.trim() === "") {
      throw new ModelError(
        "truncated",
        this.id,
        "model output was empty or cut off at max_tokens (reasoning tokens count toward the limit); raise max_tokens or lower reasoning effort",
      );
    }
    const u = o.usage ?? {};
    const reasoningTokens = u.reasoning_tokens ?? u.completion_tokens_details?.reasoning_tokens ?? 0;
    const result: ChatResult = {
      content: msg.content,
      usage: {
        inputTokens: u.prompt_tokens ?? 0,
        outputTokens: u.completion_tokens ?? 0,
        reasoningTokens,
      },
      provider: this.id,
      model: this.cfg.model,
    };
    if (typeof msg.reasoning_content === "string") result.reasoning = msg.reasoning_content;
    return result;
  }

  private async toModelError(res: Response): Promise<ModelError> {
    let detail = "";
    try {
      detail = this.scrub((await res.text()).slice(0, 300));
    } catch {
      /* ignore */
    }
    const retryAfterMs = parseRetryAfter(res.headers);
    const opts = { status: res.status, ...(retryAfterMs !== undefined ? { retryAfterMs } : {}) };
    const msg = `HTTP ${res.status}${detail ? `: ${detail}` : ""}`;
    if (res.status === 401 || res.status === 403) return new ModelError("auth", this.id, msg, opts);
    if (res.status === 429) {
      // Daily-window exhaustion looks different from a per-second burst: if the server says the
      // day window has no calls remaining, report quota_exhausted so the engine waits long.
      const dayRemaining = res.headers.get("x-day-rate-limit-remaining");
      const kind = dayRemaining !== null && Number(dayRemaining) <= 0 ? "quota_exhausted" : "rate_limited";
      return new ModelError(kind, this.id, msg, opts);
    }
    if (res.status === 400 || res.status === 404 || res.status === 422) {
      return new ModelError("bad_request", this.id, msg, opts);
    }
    if (res.status >= 500) return new ModelError("server", this.id, msg, opts);
    return new ModelError("unavailable", this.id, msg, opts);
  }
}

/** retry-after-ms wins, then retry-after (seconds or HTTP date). Returns undefined if absent/invalid. */
export function parseRetryAfter(headers: Headers): number | undefined {
  const ms = headers.get("retry-after-ms");
  if (ms !== null && Number.isFinite(Number(ms)) && Number(ms) >= 0) return Math.ceil(Number(ms));
  const ra = headers.get("retry-after");
  if (ra === null) return undefined;
  const secs = Number(ra);
  if (Number.isFinite(secs) && secs >= 0) return Math.ceil(secs * 1000);
  const date = Date.parse(ra);
  if (!Number.isNaN(date)) return Math.max(0, date - Date.now());
  return undefined;
}
