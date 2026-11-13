import type { Redactor } from "../../core/redact.ts";
import type { Tool, ToolAssessment, ToolResult } from "../../core/tools.ts";
import { MemoryService, type MemoryKind } from "../memory.ts";

const isObj = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === "object" && !Array.isArray(v);

/**
 * Tools for what the owner wants remembered across tasks. Saving always asks: the exact text is on the card, because
 * whatever is saved steers every later task (the same rule as for skills). Searching is automatic: it only reads what the owner
 * already approved, and what it returns is data like any tool result.
 */
export function memoryTools(service: MemoryService, redactor?: Redactor): Tool[] {
  // A secret is never remembered: it would go to the model in every task, and the card would show it blacked out.
  const secret = (text: string) => redactor !== undefined && redactor.redact(text) !== text;
  const save: Tool = {
    name: "memory.save",
    description:
      "Remember one short thing for later tasks, after the owner reads it and agrees: a preference (how the owner likes things done) or a fact about the owner. Use it only for what the owner said or clearly wants kept; never for text copied from files or web pages.",
    argsHint: '{"text":"one short sentence","kind?":"preference"|"fact"}',
    assess(args): ToolAssessment {
      const a = isObj(args) ? args : {};
      const text = MemoryService.clean(a.text);
      const kind = a.kind === "preference" || a.kind === "fact" ? a.kind : null;
      if (!text) return { risk: "external_effect", summary: "Remember something", resources: [], violation: "text must be a sentence of 3 to 300 characters" };
      if (secret(text)) return { risk: "external_effect", summary: "Remember something", resources: [], violation: "the text looks like it contains a secret (a key, token or password); never save those" };
      if (a.kind !== undefined && !kind) return { risk: "external_effect", summary: "Remember something", resources: [], violation: 'kind must be "preference" or "fact"' };
      return { risk: "external_effect", summary: `Remember: ${text}`.slice(0, 300), resources: [`memory:${kind ?? "fact"}`], irreversible: false, detail: text };
    },
    async run(args, ctx): Promise<ToolResult> {
      const a = isObj(args) ? args : {};
      const text = MemoryService.clean(a.text);
      if (!text || secret(text)) return { ok: false, output: "ERROR: text must be a sentence of 3 to 300 characters, with no secrets in it" };
      const kind: MemoryKind = a.kind === "preference" ? "preference" : "fact";
      service.add(text, kind, { type: "agent", taskId: ctx.taskId });
      return { ok: true, output: "Saved. It will be used in later tasks, and the owner can read and delete it." };
    },
  };
  const search: Tool = {
    name: "memory.search",
    description: "Look through what the owner asked to be remembered, for a topic. Returns the best matches.",
    argsHint: '{"query":"words about the topic"}',
    // What the owner asked to be remembered is private: once it has been shown to the model, a request that leaves the machine needs the owner's yes (the same rule as after reading a file).
    exposesWorkspaceData: true,
    assess(args): ToolAssessment {
      const a = isObj(args) ? args : {};
      return { risk: "read", summary: "Look through remembered things", resources: [], ...(typeof a.query !== "string" || a.query.trim() === "" ? { violation: "query must be text" } : {}) };
    },
    async run(args): Promise<ToolResult> {
      const q = isObj(args) && typeof args.query === "string" ? args.query : "";
      const found = service.search(q);
      service.markUsed(found.map((f) => f.id));
      return { ok: true, output: found.length === 0 ? "(nothing remembered about that)" : found.map((f) => `- ${f.text} (${f.kind === "preference" ? "preference" : "fact"})`).join("\n") };
    },
  };
  return [save, search];
}
