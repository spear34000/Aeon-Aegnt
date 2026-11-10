/** Risk classes drive default policy. */
export type Risk = "read" | "write" | "delete" | "network_read" | "network_write" | "exec" | "external_effect";

export interface ToolAssessment {
  risk: Risk;
  /** Human-readable one-liner shown in approval requests. */
  summary: string;
  /** Resources touched (absolute paths, URLs). Used for scope checks and audit. */
  resources: string[];
  /** Set by the tool when args are out of its allowed scope (path escape, private network, ...). */
  violation?: string;
}

export interface ToolContext {
  taskId: string;
  /** Provided so a tool can pass an idempotency key to the system it talks to. */
  idempotencyKey: string;
  signal?: AbortSignal;
}

export interface ToolResult {
  ok: boolean;
  /** UNTRUSTED data. Always passed back to the model wrapped as data, never as instructions. */
  output: string;
}

export interface Tool {
  name: string;
  description: string;
  /** Short arg schema for the model prompt, e.g. {"path":"string"}. */
  argsHint: string;
  /** Pure classification of a call. Must not perform the action. */
  assess(args: unknown): ToolAssessment;
  run(args: unknown, ctx: ToolContext): Promise<ToolResult>;
}

export class ToolRegistry {
  private tools = new Map<string, Tool>();
  register(tool: Tool): this {
    if (this.tools.has(tool.name)) throw new Error(`Duplicate tool: ${tool.name}`);
    this.tools.set(tool.name, tool);
    return this;
  }
  get(name: string): Tool | undefined {
    return this.tools.get(name);
  }
  list(): Tool[] {
    return [...this.tools.values()];
  }
}
