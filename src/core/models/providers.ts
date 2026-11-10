import type { Redactor } from "../redact.ts";
import { OpenAICompatibleProvider, type OpenAICompatibleConfig } from "./openai-compatible.ts";

type Common = { fetch?: typeof fetch; redactor?: Redactor; timeoutMs?: number };

/**
 * Official EXAONE API Platform (K-EXAONE 2.5).
 * Facts verified from https://docs.exaone.ai on 2026-11-10 (see docs/research):
 *  - POST https://api.exaone.ai/v1/chat/completions, Bearer auth, model id `k-exaone_v2.5`
 *  - context 524,288 tokens, text only, OpenAI-compatible, SSE streaming
 *  - tool calling and structured output are NOT documented  => "unknown" (treated as unsupported)
 *  - concrete rate limits are not published                 => null
 *
 * DECISION D9 (owner, 2026-11-10): the integration ships publicly with bring-your-own-key and without
 * any key in the repository. The EXAONE terms still apply to each user (non-commercial rights, no
 * third-party access through a product); the owner accepted the residual ambiguity. The `unreleased`
 * mechanism stays available for future integrations.
 */
export function exaoneProvider(
  opts: Common & { apiKey: string; baseUrl?: string; model?: string; reasoningEffort?: "low" | "high" | "max" },
): OpenAICompatibleProvider {
  const cfg: OpenAICompatibleConfig = {
    id: "exaone",
    baseUrl: opts.baseUrl ?? "https://api.exaone.ai/v1",
    model: opts.model ?? "k-exaone_v2.5",
    apiKey: opts.apiKey,
    external: true,
    unreleased: false,
    capabilities: {
      contextWindow: 524_288,
      toolCalling: "unknown",
      structuredOutput: "unknown",
      streaming: "supported",
      maxConcurrency: 2,
      requestsPerMinute: null,
    },
    // The API default is "max", and reasoning tokens count toward max_tokens. Agent action steps are
    // short JSON replies, so default to "low" to avoid answers that are all reasoning.
    extraBody: { reasoning_effort: opts.reasoningEffort ?? "low" },
    ...(opts.fetch ? { fetch: opts.fetch } : {}),
    ...(opts.redactor ? { redactor: opts.redactor } : {}),
    ...(opts.timeoutMs ? { timeoutMs: opts.timeoutMs } : {}),
  };
  return new OpenAICompatibleProvider(cfg);
}

/** Ollama's OpenAI-compatible endpoint. Local: prompts do not leave the machine. */
export function ollamaProvider(opts: Common & { model: string; baseUrl?: string }): OpenAICompatibleProvider {
  return new OpenAICompatibleProvider({
    id: "ollama",
    baseUrl: opts.baseUrl ?? "http://localhost:11434/v1",
    model: opts.model,
    external: false,
    capabilities: {
      contextWindow: null,
      toolCalling: "unknown",
      structuredOutput: "unknown",
      streaming: "supported",
      maxConcurrency: 1,
      requestsPerMinute: null,
    },
    ...(opts.fetch ? { fetch: opts.fetch } : {}),
    ...(opts.redactor ? { redactor: opts.redactor } : {}),
    ...(opts.timeoutMs ? { timeoutMs: opts.timeoutMs } : {}),
  });
}

/** llama.cpp `llama-server` (OpenAI-compatible). */
export function llamaCppProvider(opts: Common & { model?: string; baseUrl?: string }): OpenAICompatibleProvider {
  return new OpenAICompatibleProvider({
    id: "llamacpp",
    baseUrl: opts.baseUrl ?? "http://localhost:8080/v1",
    model: opts.model ?? "default",
    external: false,
    capabilities: {
      contextWindow: null,
      toolCalling: "unknown",
      structuredOutput: "unknown",
      streaming: "supported",
      maxConcurrency: 1,
      requestsPerMinute: null,
    },
    ...(opts.fetch ? { fetch: opts.fetch } : {}),
    ...(opts.redactor ? { redactor: opts.redactor } : {}),
    ...(opts.timeoutMs ? { timeoutMs: opts.timeoutMs } : {}),
  });
}

/** Any OpenAI-compatible service (user-supplied URL and key). `external` must be stated by the caller. */
export function openAiCompatible(
  opts: Common & { id?: string; baseUrl: string; model: string; apiKey?: string; external: boolean },
): OpenAICompatibleProvider {
  return new OpenAICompatibleProvider({
    id: opts.id ?? "openai-compatible",
    baseUrl: opts.baseUrl,
    model: opts.model,
    external: opts.external,
    ...(opts.apiKey ? { apiKey: opts.apiKey } : {}),
    capabilities: {
      contextWindow: null,
      toolCalling: "unknown",
      structuredOutput: "unknown",
      streaming: "unknown",
      maxConcurrency: 2,
      requestsPerMinute: null,
    },
    ...(opts.fetch ? { fetch: opts.fetch } : {}),
    ...(opts.redactor ? { redactor: opts.redactor } : {}),
    ...(opts.timeoutMs ? { timeoutMs: opts.timeoutMs } : {}),
  });
}
