/**
 * Model Provider Layer. A provider must never be assumed to support a feature it has not
 * documented: capabilities use "unknown" and callers treat "unknown" as unsupported.
 */
export type Support = "supported" | "unsupported" | "unknown";

export interface ModelCapabilities {
  modelId: string;
  contextWindow: number | null;
  toolCalling: Support;
  structuredOutput: Support;
  streaming: Support;
  /** Provider-side limits we know about (null = unknown/unpublished). */
  maxConcurrency: number;
  requestsPerMinute: number | null;
}

export interface ChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface ChatRequest {
  messages: ChatMessage[];
  maxTokens?: number;
  temperature?: number;
  signal?: AbortSignal;
}

export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
}

export interface ChatResult {
  /** Final answer text only; hidden reasoning is returned separately and never fed back as instructions. */
  content: string;
  reasoning?: string;
  usage: TokenUsage;
  provider: string;
  model: string;
}

export interface ModelProvider {
  /** Stable id, e.g. "exaone", "ollama". */
  readonly id: string;
  /** Whether prompts leave the user's machine. Used for data-transfer consent. */
  readonly external: boolean;
  /**
   * Marked true while an integration is not cleared for public release.
   * Release tooling must refuse to package unreleased providers.
   */
  readonly unreleased: boolean;
  capabilities(): ModelCapabilities;
  chat(req: ChatRequest): Promise<ChatResult>;
}
