/**
 * Prompt-injection defenses. Anything not typed by the owner (tool output, file contents, web
 * pages, model-visible errors) is DATA. It is wrapped with markers, cannot close its own wrapper,
 * and - most importantly - cannot expand permissions: the Policy Engine decides in code, never
 * based on text the model has read.
 */
export function wrapUntrusted(source: string, text: string): string {
  const safe = text.replace(/<\/?untrusted_data/gi, "<untrusted_data_escaped");
  return `<untrusted_data source="${source.replace(/[^\w.:-]/g, "_")}">\n${safe}\n</untrusted_data>`;
}

const SUSPICIOUS: RegExp[] = [
  /ignore (all |any )?(previous|prior|above) (instructions|prompts?)/i,
  /disregard (the |all )?(system|previous) (prompt|instructions)/i,
  /you (must|should) now\b/i,
  /new (system )?instructions?:/i,
  /\bsystem prompt\b/i,
  /<\s*\/?\s*system\s*>/i,
  /(reveal|print|send|exfiltrate).{0,40}(api[_ -]?key|secret|password|token)/i,
];

/** Heuristic only: used to raise an audit flag. Safety does not depend on it. */
export function looksLikeInjection(text: string): boolean {
  return SUSPICIOUS.some((r) => r.test(text));
}

export function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max)}\n[... truncated ${text.length - max} chars]`;
}

/** Extract the first balanced JSON object from model text (models often add prose or code fences). */
export function extractJsonObject(text: string): unknown | null {
  const start = text.indexOf("{");
  if (start < 0) return null;
  let depth = 0;
  let inStr = false;
  let esc = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i]!;
    if (inStr) {
      if (esc) esc = false;
      else if (ch === "\\") esc = true;
      else if (ch === '"') inStr = false;
      continue;
    }
    if (ch === '"') inStr = true;
    else if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) {
        try {
          return JSON.parse(text.slice(start, i + 1));
        } catch {
          return null;
        }
      }
    }
  }
  return null;
}
