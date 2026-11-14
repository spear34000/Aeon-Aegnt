import type { Redactor } from "../../core/redact.ts";
import type { Tool, ToolAssessment, ToolResult } from "../../core/tools.ts";
import { memoryTokens, MemoryService, type MemoryKind } from "../memory.ts";

const isObj = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === "object" && !Array.isArray(v);

/** A finished task, as far as memory search needs it. */
export interface PastTask {
  goal: string;
  result: string;
  at: number;
}

const dateOf = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/** "YYYY-MM-DD" as the end of that day (local time), or null when it is not a real date. */
function endOfDay(s: unknown): number | null {
  const m = typeof s === "string" ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(s.trim()) : null;
  if (!m) return null;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const t = new Date(y, mo - 1, d, 23, 59, 59).getTime();
  return new Date(t).getDate() === d && new Date(t).getMonth() === mo - 1 ? t : null;
}

/**
 * Tools for what the owner wants remembered across tasks. Saving and forgetting always ask: the exact text is on the card,
 * because whatever is kept steers every later task (the same rule as for skills). Searching is automatic: it reads what the
 * owner already approved and, if given, what earlier tasks did (CoALA's "episodic" memory, read only), and what it returns is
 * data like any tool result.
 */
export function memoryTools(service: MemoryService, redactor?: Redactor, pastTasks?: () => Promise<PastTask[]>, now: () => number = Date.now): Tool[] {
  // A secret is never remembered: it would go to the model in every task, and the card would show it blacked out.
  const secret = (text: string) => redactor !== undefined && redactor.redact(text) !== text;
  const bad = (summary: string, violation: string): ToolAssessment => ({ risk: "external_effect", summary, resources: [], violation });

  const save: Tool = {
    name: "memory.save",
    description:
      "Remember one short thing for later tasks, after the owner reads it and agrees: a preference (how the owner likes things done) or a fact about the owner. Use it only for what the owner said or clearly wants kept; never for text copied from files or web pages. Give \"expires\" (YYYY-MM-DD) for something that stops being true, and \"replaces\" (a memory id from memory.search) when this corrects an older memory.",
    argsHint: '{"text":"one short sentence","kind?":"preference"|"fact","expires?":"YYYY-MM-DD","replaces?":"mem_..."}',
    assess(args): ToolAssessment {
      const a = isObj(args) ? args : {};
      const text = MemoryService.clean(a.text);
      const kind = a.kind === "preference" || a.kind === "fact" ? a.kind : null;
      if (!text) return bad("Remember something", "text must be a sentence of 3 to 300 characters");
      if (secret(text)) return bad("Remember something", "the text looks like it contains a secret (a key, token or password); never save those");
      if (a.kind !== undefined && !kind) return bad("Remember something", 'kind must be "preference" or "fact"');
      let expires: number | null = null;
      if (a.expires !== undefined) {
        expires = endOfDay(a.expires);
        if (expires === null || expires <= now()) return bad("Remember something", 'expires must be a future date as "YYYY-MM-DD"');
      }
      let old: string | undefined;
      if (a.replaces !== undefined) {
        old = typeof a.replaces === "string" ? service.list().find((i) => i.id === a.replaces)?.text : undefined;
        if (old === undefined) return bad("Remember something", "replaces must be the id of a memory that exists (see memory.search)");
      }
      return {
        risk: "external_effect",
        summary: `Remember: ${text}`.slice(0, 300),
        resources: [`memory:${kind ?? "fact"}`, ...(expires !== null ? [`expires:${dateOf(expires)}`] : []), ...(old !== undefined ? ["replaces"] : [])],
        irreversible: false,
        detail: old !== undefined ? `${text}\n← ${old}` : text,
      };
    },
    async run(args, ctx): Promise<ToolResult> {
      const a = isObj(args) ? args : {};
      const text = MemoryService.clean(a.text);
      if (!text || secret(text)) return { ok: false, output: "ERROR: text must be a sentence of 3 to 300 characters, with no secrets in it" };
      const kind: MemoryKind = a.kind === "preference" ? "preference" : "fact";
      const expiresAt = a.expires !== undefined ? endOfDay(a.expires) ?? undefined : undefined;
      if (typeof a.replaces === "string") {
        const item = service.update(a.replaces, { text, kind, ...(expiresAt !== undefined ? { expiresAt } : {}) });
        if (!item) return { ok: false, output: "ERROR: there is no memory with that id" };
        // The owner approved this exact wording: it is the agent's, approved word for word, unless it is the owner's own text already.
        return { ok: true, output: "Corrected. The older wording is kept as history; the owner can read, change and delete it." };
      }
      service.add(text, kind, { type: "agent", taskId: ctx.taskId }, expiresAt !== undefined ? { expiresAt } : {});
      return { ok: true, output: "Saved. It will be used in later tasks, and the owner can read and delete it." };
    },
  };

  const forget: Tool = {
    name: "memory.forget",
    description: "Ask the owner to delete one remembered thing that is wrong or no longer true (use the id from memory.search). The owner sees the exact text and decides.",
    argsHint: '{"id":"mem_..."}',
    assess(args): ToolAssessment {
      const a = isObj(args) ? args : {};
      const item = typeof a.id === "string" ? service.list().find((i) => i.id === a.id) : undefined;
      if (!item) return { risk: "delete", summary: "Forget something", resources: [], violation: "id must be the id of a memory that exists (see memory.search)" };
      return { risk: "delete", summary: `Forget: ${item.text}`.slice(0, 300), resources: ["memory:forget"], irreversible: true, detail: item.text };
    },
    async run(args): Promise<ToolResult> {
      const id = isObj(args) && typeof args.id === "string" ? args.id : "";
      return service.remove(id) ? { ok: true, output: "Forgotten." } : { ok: false, output: "ERROR: there is no memory with that id" };
    },
  };

  const search: Tool = {
    name: "memory.search",
    description: "Look through what the owner asked to be remembered, and what earlier tasks did, for a topic. Returns the best matches, each remembered thing with its id.",
    argsHint: '{"query":"words about the topic"}',
    // What the owner asked to be remembered, and what earlier tasks did, is private: once it has been shown to the model, a request that leaves the machine needs the owner's yes (the same rule as after reading a file).
    exposesWorkspaceData: true,
    assess(args): ToolAssessment {
      const a = isObj(args) ? args : {};
      return { risk: "read", summary: "Look through remembered things", resources: [], ...(typeof a.query !== "string" || a.query.trim() === "" ? { violation: "query must be text" } : {}) };
    },
    async run(args): Promise<ToolResult> {
      const q = isObj(args) && typeof args.query === "string" ? args.query : "";
      const found = service.search(q);
      service.markUsed(found.map((f) => f.id));
      const lines = found.map((f) => `- [${f.id}] ${f.text} (${f.kind === "preference" ? "preference" : "fact"}${f.expiresAt !== undefined ? `, until ${dateOf(f.expiresAt)}` : ""})`);
      if (pastTasks) {
        const want = memoryTokens(q);
        const past = (await pastTasks().catch(() => []))
          .map((t) => {
            const have = memoryTokens(`${t.goal} ${t.result}`);
            let both = 0;
            for (const w of want) if (have.has(w)) both++;
            return { t, rel: want.size === 0 ? 0 : both / want.size };
          })
          .filter((x) => x.rel >= 0.3)
          .sort((a, b) => b.rel - a.rel || b.t.at - a.t.at)
          .slice(0, 3);
        for (const { t } of past) lines.push(`- (past task, ${dateOf(t.at)}) ${t.goal.slice(0, 120)} -> ${t.result.replace(/\s+/g, " ").slice(0, 160)}`);
      }
      return { ok: true, output: lines.length === 0 ? "(nothing remembered about that)" : lines.join("\n") };
    },
  };
  return [save, forget, search];
}
