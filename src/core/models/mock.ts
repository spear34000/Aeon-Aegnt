import { ModelError } from "../errors.ts";
import type { ChatRequest, ChatResult, ModelCapabilities, ModelProvider } from "./types.ts";

export type MockReply = string | ModelError | ((req: ChatRequest) => string | ModelError);

/** Deterministic provider for tests. NOT a real model: results using it are mock-verified only. */
export class MockProvider implements ModelProvider {
  readonly id: string;
  readonly external: boolean;
  readonly unreleased = false;
  readonly calls: ChatRequest[] = [];
  private queue: MockReply[];

  constructor(id: string, replies: MockReply[], opts: { external?: boolean } = {}) {
    this.id = id;
    this.queue = [...replies];
    this.external = opts.external ?? false;
  }

  capabilities(): ModelCapabilities {
    return {
      modelId: `mock-${this.id}`,
      contextWindow: 8192,
      toolCalling: "unsupported",
      structuredOutput: "unsupported",
      streaming: "unsupported",
      maxConcurrency: 4,
      requestsPerMinute: null,
    };
  }

  push(...replies: MockReply[]): void {
    this.queue.push(...replies);
  }

  async chat(req: ChatRequest): Promise<ChatResult> {
    this.calls.push(req);
    const next = this.queue.shift();
    if (next === undefined) throw new Error(`MockProvider ${this.id}: no scripted reply left`);
    const reply = typeof next === "function" ? next(req) : next;
    if (reply instanceof ModelError) throw reply;
    return {
      content: reply,
      usage: { inputTokens: 10, outputTokens: 5, reasoningTokens: 0 },
      provider: this.id,
      model: `mock-${this.id}`,
    };
  }
}
