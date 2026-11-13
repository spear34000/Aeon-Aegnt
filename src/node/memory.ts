import { createHash, randomBytes } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

/**
 * What the owner wants the agent to remember across tasks, kept as plain text the owner can read, add to and delete.
 * Design, from the sources read for it (docs/research/2026-11-14-agent-behaviour.md):
 *  - Generative Agents: a memory stream, retrieved by recency, importance and relevance; the three scores are summed.
 *    Here relevance is word overlap (no embeddings: nothing to download or pay for), recency decays with days, and importance
 *    is higher for what the owner wrote themselves.
 *  - MemGPT / Anthropic: a small part is always in the prompt, the rest is fetched on demand (the `memory.search` tool).
 *  - Reflexion: a bounded set, not an ever-growing log.
 * Nothing is saved by the agent without the owner reading the exact text on an approval card (the agent's tool asks), because
 * what is saved steers every later task: it is the same trust rule as for skills.
 */

export type MemoryKind = "preference" | "fact";

export type MemorySource =
  /** The owner typed it. */
  | { type: "owner" }
  /** The agent proposed it during a task and the owner approved the exact text. */
  | { type: "agent"; taskId: string };

export interface MemoryItem {
  id: string;
  text: string;
  kind: MemoryKind;
  source: MemorySource;
  createdAt: number;
  lastUsedAt?: number;
  uses: number;
}

export const MEMORY_TEXT_MAX = 300;
export const MEMORY_MAX_ITEMS = 200;
const DAY_MS = 86_400_000;

/** Words that say nothing about a topic. Without this, "the" in a goal would make every memory with "the" look relevant. */
const STOP = new Set(
  "a an and are as at be but by can do for from has have how i if in into is it its my me of on or our so than that the their them then there these they this to up was we were what when where which who will with you your about all any not out new get let make want need please".split(" "),
);
/** Endings and helper words that make Korean pairs of characters match without meaning anything. */
const STOP_KO = new Set("에서 으로 에게 에는 에도 니다 습니 합니 하는 하고 하다 해서 해줘 해주 있는 있다 없는 이다 입니 그리 그런 이런 저런".split(" "));

/** Words of a text: lower-cased letters and digits, plus character pairs for Hangul and CJK so that endings do not hide a match. */
export function memoryTokens(s: string): Set<string> {
  const out = new Set<string>();
  const words = s.toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, " ").split(/\s+/).filter(Boolean);
  for (const w of words) {
    const cjk = /[ㄱ-ㆎ가-힣一-鿿]/.test(w);
    if (w.length > 1 && !STOP.has(w) && (cjk || w.length > 2 || /\d/.test(w))) out.add(w);
    if (cjk) for (let i = 0; i + 2 <= w.length; i++) if (!STOP_KO.has(w.slice(i, i + 2))) out.add(w.slice(i, i + 2));
  }
  return out;
}

/** How much of the memory's words show up in the goal: 0 to 1. */
function relevance(goal: Set<string>, memory: Set<string>): number {
  if (memory.size === 0 || goal.size === 0) return 0;
  let both = 0;
  for (const t of memory) if (goal.has(t)) both++;
  return both / memory.size;
}

export class MemoryService {
  private readonly file: string;
  private readonly now: () => number;

  constructor(file: string, now: () => number = Date.now) {
    this.file = file;
    this.now = now;
  }

  list(): MemoryItem[] {
    if (!existsSync(this.file)) return [];
    try {
      const j = JSON.parse(readFileSync(this.file, "utf8")) as { version?: number; items?: MemoryItem[] };
      if (j?.version !== 1 || !Array.isArray(j.items)) return [];
      return j.items.filter((i) => i && typeof i.id === "string" && typeof i.text === "string" && (i.kind === "preference" || i.kind === "fact"));
    } catch {
      return [];
    }
  }

  private save(items: MemoryItem[]): void {
    mkdirSync(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp-${process.pid}`;
    writeFileSync(tmp, JSON.stringify({ version: 1, items }), { mode: 0o600 });
    renameSync(tmp, this.file);
  }

  /** The text as it will be stored: one line, no markup brackets, cut at 300 characters. Null when nothing is left. */
  static clean(text: unknown): string | null {
    if (typeof text !== "string") return null;
    const t = text.replace(/[\u0000-\u001f<>]/g, " ").replace(/\s+/g, " ").trim().slice(0, MEMORY_TEXT_MAX);
    return t.length >= 3 ? t : null;
  }

  /** Adds an item, or returns the existing one with the same words. At most 200 are kept: the one used longest ago makes room. */
  add(text: string, kind: MemoryKind, source: MemorySource): MemoryItem {
    const clean = MemoryService.clean(text);
    if (!clean) throw new Error("the memory is too short");
    const items = this.list();
    const same = createHash("sha1").update(clean.toLowerCase().replace(/\s+/g, "")).digest("hex");
    const dup = items.find((i) => createHash("sha1").update(i.text.toLowerCase().replace(/\s+/g, "")).digest("hex") === same);
    if (dup) return dup;
    const item: MemoryItem = { id: `mem_${randomBytes(5).toString("hex")}`, text: clean, kind, source, createdAt: this.now(), uses: 0 };
    items.push(item);
    while (items.length > MEMORY_MAX_ITEMS) {
      let oldest = 0;
      items.forEach((i, idx) => {
        if ((i.lastUsedAt ?? i.createdAt) < (items[oldest]!.lastUsedAt ?? items[oldest]!.createdAt)) oldest = idx;
      });
      items.splice(oldest, 1);
    }
    this.save(items);
    return item;
  }

  remove(id: string): boolean {
    const items = this.list();
    const rest = items.filter((i) => i.id !== id);
    if (rest.length === items.length) return false;
    this.save(rest);
    return true;
  }

  /** Scores for a goal: recency + importance + relevance, summed (Generative Agents), best first. */
  private ranked(goal: string): { item: MemoryItem; rel: number; score: number }[] {
    const g = memoryTokens(goal);
    const now = this.now();
    return this.list()
      .map((item) => {
        const rel = relevance(g, memoryTokens(item.text));
        const recency = 0.97 ** Math.max(0, (now - (item.lastUsedAt ?? item.createdAt)) / DAY_MS);
        const importance = item.source.type === "owner" ? 1 : 0.6;
        return { item, rel, score: recency + importance + rel * 2 };
      })
      .sort((a, b) => b.score - a.score);
  }

  /**
   * What goes into the prompt of a task: how the owner likes things done (preferences, always, up to 4) and the facts that
   * match the goal. Held to a character budget so memory never crowds out the task.
   */
  relevant(goal: string, maxItems = 8, maxChars = 1200): MemoryItem[] {
    const ranked = this.ranked(goal);
    const picks: MemoryItem[] = [];
    let chars = 0;
    const take = (item: MemoryItem) => {
      if (picks.length >= maxItems || chars + item.text.length > maxChars || picks.includes(item)) return;
      picks.push(item);
      chars += item.text.length;
    };
    ranked.filter((r) => r.item.kind === "preference").slice(0, 4).forEach((r) => take(r.item));
    ranked.filter((r) => r.item.kind === "fact" && r.rel >= 0.15).forEach((r) => take(r.item));
    return picks;
  }

  /** For the search tool: the best matches, with some relevance, whatever the kind. */
  search(query: string, limit = 8): MemoryItem[] {
    return this.ranked(query).filter((r) => r.rel > 0).slice(0, limit).map((r) => r.item);
  }

  /** Remembers that these items were shown to a task (recency counts from here). */
  markUsed(ids: string[]): void {
    if (ids.length === 0) return;
    const items = this.list();
    const at = this.now();
    let changed = false;
    for (const i of items) {
      if (ids.includes(i.id)) {
        i.lastUsedAt = at;
        i.uses += 1;
        changed = true;
      }
    }
    if (changed) this.save(items);
  }
}
